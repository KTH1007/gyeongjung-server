package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// CI 러너가 SSH 강제 명령으로 사전 점검과 배포를 부르는 스크립트의 약속을 지킨다. ssh를 가짜로 바꿔 끼워서
// 어떤 옵션과 어떤 원격 명령으로 부르는지, 종료 코드를 어떻게 돌려주는지만 확인한다.
// 진짜 sshd 앞의 동작은 로컬 임시 sshd로 따로 시험한다.
class RemoteDeployScriptTest {

    private static final String TAG = "2222222";

    // 마지막 인자가 원격 명령이다. FAKE_PREFLIGHT_EXIT와 FAKE_DEPLOY_EXIT로 종료 코드를 정한다.
    private static final String FAKE_SSH =
            """
            #!/bin/bash
            echo "ssh $*" >> "$FAKE_STATE/calls.log"
            case "${!#}" in
              preflight*) echo "가짜 점검"; exit "${FAKE_PREFLIGHT_EXIT:-0}" ;;
              deploy*) echo "가짜 배포"; exit "${FAKE_DEPLOY_EXIT:-0}" ;;
            esac
            exit 99
            """;

    @Test
    void 점검을_먼저_하고_배포를_하며_원격_명령은_정해진_두_가지뿐이다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result result = run(temp, Map.of(), TAG);

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls()).hasSize(2);
        assertThat(result.calls().get(0)).endsWith(" preflight " + TAG);
        assertThat(result.calls().get(1)).endsWith(" deploy " + TAG);
    }

    @Test
    void 호스트_키를_고정하고_키_인증만_쓰며_처음_보는_호스트를_믿지_않는다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result result = run(temp, Map.of(), TAG);

        // then
        String key = temp.resolve("id_deploy").toString();
        String known = temp.resolve("known_hosts").toString();
        assertThat(result.calls())
                .isNotEmpty()
                .allSatisfy(call ->
                        assertThat(call)
                                .contains(
                                        "-i " + key,
                                        "-o IdentitiesOnly=yes",
                                        "-o BatchMode=yes",
                                        "-o StrictHostKeyChecking=yes",
                                        "-o UserKnownHostsFile=" + known,
                                        " deployer@deploy.example.com ")
                                .doesNotContain(
                                        "StrictHostKeyChecking=no", "accept-new", "/dev/null"));
    }

    @Test
    void 태그가_올바르지_않으면_아무것도_실행하지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        List<String> tags = List.of("latest", "abc", "ZZZZZZZ", "1234567;id", "$(id)", "");

        // when
        List<String> accepted = new ArrayList<>();
        int i = 0;
        for (String tag : tags) {
            Result result = run(temp.resolve("t" + i++), Map.of(), tag);
            if (result.exitCode() != 2 || !result.calls().isEmpty()) {
                accepted.add(tag + " -> 종료 코드 " + result.exitCode());
            }
        }
        Result noTag = run(temp.resolve("none"), Map.of());

        // then
        assertThat(accepted).as("받아들인 태그").isEmpty();
        assertThat(noTag.exitCode()).isEqualTo(2);
    }

    @Test
    void 접속_정보나_파일이_빠지면_아무것도_실행하지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 호스트 키 파일이 없을 때 처음 보는 호스트를 믿는 쪽으로 물러서지 않는지도 본다
        List<Map<String, String>> broken =
                List.of(
                        Map.of("DEPLOY_HOST", ""),
                        Map.of("DEPLOY_USER", ""),
                        Map.of("DEPLOY_SSH_KEY_FILE", ""),
                        Map.of("DEPLOY_KNOWN_HOSTS_FILE", ""),
                        Map.of("DEPLOY_SSH_KEY_FILE", temp.resolve("none-key").toString()),
                        Map.of("DEPLOY_KNOWN_HOSTS_FILE", temp.resolve("none-known").toString()));

        // when
        List<String> accepted = new ArrayList<>();
        int i = 0;
        for (Map<String, String> scenario : broken) {
            Result result = run(temp.resolve("e" + i++), scenario, TAG);
            if (result.exitCode() != 2 || !result.calls().isEmpty()) {
                accepted.add(scenario + " -> 종료 코드 " + result.exitCode());
            }
        }

        // then
        assertThat(accepted).as("받아들인 경우").isEmpty();
    }

    @Test
    void 사전_점검이_실패하면_배포하지_않고_연결_실패는_구분해서_알린다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result blocked = run(temp.resolve("blocked"), Map.of("FAKE_PREFLIGHT_EXIT", "1"), TAG);
        Result unreachable = run(temp.resolve("down"), Map.of("FAKE_PREFLIGHT_EXIT", "255"), TAG);

        // then
        assertThat(blocked.exitCode()).isEqualTo(12);
        assertThat(blocked.calls()).hasSize(1);
        assertThat(blocked.output()).contains("사전 점검");
        assertThat(unreachable.exitCode()).isEqualTo(255);
        assertThat(unreachable.calls()).hasSize(1);
        assertThat(unreachable.output()).contains("SSH 연결");
    }

    @Test
    void 배포의_종료_코드를_그대로_돌려주고_무슨_뜻인지_알려준다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        Map<String, String> meanings = new LinkedHashMap<>();
        meanings.put("10", "아무것도 바꾸지 않았다");
        meanings.put("11", "다른 배포가 진행 중");
        meanings.put("20", "이전 버전으로 되돌렸다");
        meanings.put("30", "수동 조치");
        meanings.put("126", "허용되지 않은 명령");
        meanings.put("255", "SSH 연결");

        // when
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> entry : meanings.entrySet()) {
            Path dir = temp.resolve("c" + entry.getKey());
            Result result = run(dir, Map.of("FAKE_DEPLOY_EXIT", entry.getKey()), TAG);
            if (result.exitCode() != Integer.parseInt(entry.getKey())
                    || !result.output().contains(entry.getValue())) {
                wrong.add(entry.getKey() + " -> " + result.exitCode());
            }
        }

        // then
        assertThat(wrong).as("종료 코드나 안내가 틀린 경우").isEmpty();
    }

    @Test
    void DEPLOY_SKIP_PREFLIGHT가_정확히_1일_때만_점검을_건너뛴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        List<String> notSkipping = List.of("true", "yes", "0", "");

        // when
        Result skipped = run(temp.resolve("skip"), Map.of("DEPLOY_SKIP_PREFLIGHT", "1"), TAG);
        List<String> skippedByMistake = new ArrayList<>();
        for (String value : notSkipping) {
            Result result =
                    run(temp.resolve("v" + value), Map.of("DEPLOY_SKIP_PREFLIGHT", value), TAG);
            if (result.calls().size() != 2) {
                skippedByMistake.add("[" + value + "]");
            }
        }

        // then
        assertThat(skipped.calls()).hasSize(1);
        assertThat(skipped.calls().get(0)).endsWith(" deploy " + TAG);
        assertThat(skippedByMistake).as("1이 아닌데 점검을 건너뛴 값").isEmpty();
    }

    @Test
    void 비밀을_출력하지_않고_포트는_숫자만_받는다(@TempDir Path temp) throws IOException, InterruptedException {
        // when
        Result result = run(temp.resolve("ok"), Map.of("DEPLOY_PORT", "2222"), TAG);
        Result defaultPort = run(temp.resolve("default"), Map.of(), TAG);
        List<String> accepted = new ArrayList<>();
        for (String port : List.of("abc", "0", "65536", "22;id", "-1")) {
            Result bad = run(temp.resolve("p" + port.hashCode()), Map.of("DEPLOY_PORT", port), TAG);
            if (bad.exitCode() != 2 || !bad.calls().isEmpty()) {
                accepted.add(port);
            }
        }

        // then: 키 내용이나 명령 추적(set -x)이 로그에 남으면 안 된다
        assertThat(result.calls()).allMatch(call -> call.contains("-p 2222 "));
        assertThat(defaultPort.calls()).allMatch(call -> call.contains("-p 22 "));
        assertThat(result.output() + String.join("\n", result.calls()))
                .doesNotContain("SECRET-KEY-CONTENT");
        assertThat(result.output().lines()).noneMatch(line -> line.startsWith("+"));
        assertThat(accepted).as("받아들인 포트").isEmpty();
    }

    private record Result(int exitCode, String output, List<String> calls) {}

    private static Result run(Path temp, Map<String, String> scenario, String... args)
            throws IOException, InterruptedException {
        Path state = prepareFakes(temp);

        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(RepositoryFiles.root().resolve("ops/remote-deploy.sh").toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        configure(builder.environment(), temp, scenario);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("remote-deploy.sh가 60초 안에 끝나야 한다").isTrue();
        return new Result(
                process.exitValue(), output, Files.readAllLines(state.resolve("calls.log")));
    }

    // 가짜 ssh, 호출 기록, 가짜 키 파일(내용이 출력에 새면 안 된다), 호스트 키 파일을 만든다.
    private static Path prepareFakes(Path temp) throws IOException {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path state = Files.createDirectories(temp.resolve("state"));
        Files.createFile(state.resolve("calls.log"));
        Path ssh = bin.resolve("ssh");
        Files.writeString(ssh, FAKE_SSH);
        assertThat(ssh.toFile().setExecutable(true)).isTrue();
        Files.writeString(temp.resolve("id_deploy"), "SECRET-KEY-CONTENT\n");
        Files.writeString(temp.resolve("known_hosts"), "deploy.example.com ssh-ed25519 AAAA\n");
        return state;
    }

    private static void configure(
            Map<String, String> env, Path temp, Map<String, String> scenario) {
        env.keySet().removeIf(name -> name.startsWith("DEPLOY_") || name.startsWith("FAKE_"));
        env.put("FAKE_STATE", temp.resolve("state").toString());
        env.put("DEPLOY_SSH_BIN", temp.resolve("bin/ssh").toString());
        env.put("DEPLOY_HOST", "deploy.example.com");
        env.put("DEPLOY_USER", "deployer");
        env.put("DEPLOY_SSH_KEY_FILE", temp.resolve("id_deploy").toString());
        env.put("DEPLOY_KNOWN_HOSTS_FILE", temp.resolve("known_hosts").toString());
        env.putAll(scenario);
    }
}
