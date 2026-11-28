package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

// 배포 워크플로(.github/workflows/deploy.yml)가 정한 안전 규칙을 지키는지 YAML을 읽어 확인한다.
// 워크플로는 GitHub에서만 돌아서 로컬에서는 이 정적 검사와 actionlint까지만 할 수 있다. 실제 실행은 첫 배포 때 확인한다.
class DeployWorkflowTest {

    private static final Pattern SECRET = Pattern.compile("secrets\\.([A-Za-z_]+)");

    @Test
    void 수동_실행만_하고_입력은_태그와_점검_건너뛰기_둘이다() throws IOException {
        // given
        Map<Object, Object> on = trigger();
        Map<Object, Object> inputs = map(map(on.get("workflow_dispatch")).get("inputs"));

        // then: 자동 실행(push, pull_request, schedule)이 없어야 한다. 자동 배포는 #39에서 정한다
        assertThat(on).containsOnlyKeys("workflow_dispatch");
        assertThat(inputs).containsOnlyKeys("tag", "skip_preflight");
        assertThat(map(inputs.get("tag")))
                .containsEntry("type", "string")
                .containsEntry("required", false);
        assertThat(map(inputs.get("skip_preflight")))
                .containsEntry("type", "boolean")
                .containsEntry("default", false);
    }

    @Test
    void 권한은_읽기가_기본이고_패키지_쓰기는_빌드_잡에만_있다() throws IOException {
        // then
        assertThat(map(workflow().get("permissions"))).containsOnlyKeys("contents");
        assertThat(map(workflow().get("permissions"))).containsEntry("contents", "read");
        assertThat(map(job("build").get("permissions")))
                .containsOnlyKeys("contents", "packages")
                .containsEntry("packages", "write")
                .containsEntry("contents", "read");
        assertThat(job("deploy")).doesNotContainKey("permissions");
        assertThat(text())
                .doesNotContain("write-all", "id-token", "actions: write", "contents: write");
    }

    @Test
    void 서드파티_액션이나_자체_러너를_쓰지_않고_체크아웃은_토큰을_남기지_않는다() throws IOException {
        // given
        List<String> uses =
                allSteps().stream()
                        .map(step -> step.get("uses"))
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .toList();

        // then
        assertThat(uses).isNotEmpty().allMatch(use -> use.startsWith("actions/"));
        assertThat(allSteps())
                .filteredOn(step -> String.valueOf(step.get("uses")).startsWith("actions/checkout"))
                .isNotEmpty()
                .allSatisfy(step ->
                        assertThat(map(step.get("with")))
                                .containsEntry("persist-credentials", false));
        assertThat(text()).doesNotContain("self-hosted");
    }

    @Test
    void run_스크립트에_표현식을_직접_넣지_않는다() throws IOException {
        // then: 입력이나 비밀이 셸 문법으로 해석되는 길을 막으려고 모두 env로 넘긴다
        assertThat(runScripts()).isNotEmpty().noneMatch(script -> script.contains("${{"));
    }

    @Test
    void 빌드는_ARM_러너에서_스크립트로_하고_로그인은_표준입력으로_하며_소유자를_소문자로_바꾼다() throws IOException {
        // given
        String runs = String.join("\n", runScripts());

        // then
        assertThat(job("build")).containsEntry("runs-on", "ubuntu-24.04-arm");
        assertThat(runs).contains("ops/build-push.sh", "--password-stdin", "${OWNER,,}");
        assertThat(runs).doesNotContain("docker build", "docker push", " -p ", "--password ");
    }

    @Test
    void 서버_배포는_스크립트로만_하고_호스트_키_확인을_끄지_않는다() throws IOException {
        // given
        String deployRuns =
                steps("deploy").stream()
                        .map(step -> step.get("run"))
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .reduce("", (all, script) -> all + "\n" + script);

        // then: ssh를 직접 부르지 않으므로 호스트 키 고정과 점검, 종료 코드 처리가 스크립트 한 곳에 모인다
        assertThat(deployRuns).contains("ops/remote-deploy.sh", "umask 077", "trap");
        assertThat(String.join("\n", runScripts()))
                .doesNotContainPattern("\\b(ssh|scp|rsync|ssh-keyscan)\\b");
        assertThat(text()).doesNotContain("StrictHostKeyChecking");
    }

    @Test
    void 비밀은_정해진_이름만_배포_단계의_환경변수로_쓴다() throws IOException {
        // given
        Matcher matcher = SECRET.matcher(text());
        TreeSet<String> names = new TreeSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }

        // then
        assertThat(names)
                .containsExactlyInAnyOrder(
                        "DEPLOY_HOST",
                        "DEPLOY_KNOWN_HOSTS",
                        "DEPLOY_SSH_KEY",
                        "DEPLOY_USER",
                        "GITHUB_TOKEN");
        assertThat(workflow()).doesNotContainKey("env");
        assertThat(job("deploy")).doesNotContainKey("env");
        assertThat(steps("build").stream().map(step -> String.valueOf(step.get("env"))))
                .noneMatch(env -> env.contains("secrets.DEPLOY_"));
    }

    @Test
    void 태그는_검증하고_입력이_없으면_이_커밋을_빌드해서_쓰며_latest를_쓰지_않는다() throws IOException {
        // then
        assertThat(String.valueOf(job("build").get("if"))).contains("inputs.tag == ''");
        assertThat(String.valueOf(job("deploy").get("if")))
                .contains(
                        "!cancelled()",
                        "needs.build.result == 'success'",
                        "needs.build.result == 'skipped'");
        assertThat(String.join("\n", runScripts())).contains("^[0-9a-f]{7,40}$");
        assertThat(steps("build").stream().map(step -> String.valueOf(step.get("env"))))
                .anyMatch(env -> env.contains("${{ github.sha }}"));
        assertThat(text()).doesNotContain("latest");
    }

    @Test
    void 배포는_main에서만_한_번에_하나씩_돌고_모든_잡에_시간_제한이_있다() throws IOException {
        // then
        assertThat(String.valueOf(job("build").get("if")))
                .contains("github.ref == 'refs/heads/main'");
        assertThat(String.valueOf(job("deploy").get("if")))
                .contains("github.ref == 'refs/heads/main'");
        assertThat(map(workflow().get("concurrency")))
                .containsEntry("group", "deploy")
                .containsEntry("cancel-in-progress", false);
        for (String name : List.of("build", "deploy")) {
            assertThat(job(name).get("timeout-minutes")).isInstanceOf(Integer.class);
        }
    }

    private static Map<Object, Object> workflow() throws IOException {
        return map(new Yaml().load(text()));
    }

    private static String text() throws IOException {
        return Files.readString(RepositoryFiles.root().resolve(".github/workflows/deploy.yml"));
    }

    // YAML 1.1에서는 on이 불리언 true로 읽히므로 두 가지 키를 모두 본다.
    private static Map<Object, Object> trigger() throws IOException {
        Map<Object, Object> workflow = workflow();
        return map(workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE));
    }

    private static Map<Object, Object> job(String name) throws IOException {
        return map(map(workflow().get("jobs")).get(name));
    }

    private static List<Map<Object, Object>> steps(String jobName) throws IOException {
        return list(job(jobName).get("steps")).stream().map(DeployWorkflowTest::map).toList();
    }

    private static List<Map<Object, Object>> allSteps() throws IOException {
        List<Map<Object, Object>> all = new ArrayList<>();
        for (Object name : map(workflow().get("jobs")).keySet()) {
            all.addAll(steps(String.valueOf(name)));
        }
        return all;
    }

    private static List<String> runScripts() throws IOException {
        return allSteps().stream()
                .map(step -> step.get("run"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object value) {
        return value == null ? Map.of() : (Map<Object, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }
}
