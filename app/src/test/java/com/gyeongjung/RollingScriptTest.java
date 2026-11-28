package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 서비스 복제본을 하나씩 교체하는 롤링 스크립트의 약속을 지킨다. docker와 sleep을 가짜로 바꿔 끼운다.
// 진짜 compose와 nginx 앞에서 읽기 실패가 0건인지는 ops/nginx/test/verify-rolling.sh가 맡는다.
class RollingScriptTest {

    private static final String OLD = "oldhash";
    private static final String NEW = "newhash";
    private static final String UP_SURGE =
            "docker compose -p gyeongjung up -d --no-build --no-deps --no-recreate --scale api=3 api";
    private static final Pattern DANGEROUS =
            Pattern.compile(
                    "\\b(down|kill|prune)\\b|--force-recreate|--volumes|docker rm -f|compose\\b.*\\brm\\b");

    // 컨테이너는 FAKE_STATE/containers/<id> 파일이고 "hash=..."와 "health=..."를 담는다.
    // health가 "starting:N"이면 N번 확인할 때까지 starting이고 그 뒤 healthy다. 호출은 calls.log에 남긴다.
    private static final String FAKE_DOCKER =
            """
            #!/bin/bash
            echo "docker $*" >> "$FAKE_STATE/calls.log"
            C="$FAKE_STATE/containers"
            case "$1" in
              compose)
                shift
                if [ "$1" = "-p" ]; then shift 2; fi
                case "$1" in
                  config)
                    case "$*" in
                      *--hash*)
                        [ -n "${FAKE_NO_SERVICE:-}" ] || echo "api ${FAKE_WANT_HASH:-newhash}"
                        ;;
                      *)
                        hc='"healthcheck":{"test":["CMD","true"]},'
                        [ -n "${FAKE_NO_HEALTHCHECK:-}" ] && hc=""
                        printf '{"services":{"api":{%s"deploy":{"replicas":%s}}}}\\n' "$hc" "${FAKE_REPLICAS:-2}"
                        ;;
                    esac
                    ;;
                  ps)
                    ls "$C"
                    ;;
                  up)
                    target=${FAKE_REPLICAS:-2}
                    for a in "$@"; do case "$a" in api=*) target=${a#api=} ;; esac; done
                    while [ "$(ls "$C" | wc -l)" -lt "$target" ]; do
                      seq=$(( $(cat "$FAKE_STATE/seq" 2> /dev/null || echo 0) + 1 ))
                      echo "$seq" > "$FAKE_STATE/seq"
                      printf 'hash=%s\\nhealth=%s\\n' "${FAKE_NEW_HASH:-${FAKE_WANT_HASH:-newhash}}" "${FAKE_NEW_HEALTH:-healthy}" > "$C/new$seq"
                    done
                    ;;
                esac
                ;;
              inspect)
                id=${!#}
                case "$*" in
                  *config-hash*) sed -n 's/^hash=//p' "$C/$id" ;;
                  *Health*)
                    status=$(sed -n 's/^health=//p' "$C/$id")
                    case "$status" in
                      starting:*)
                        left=${status#starting:}
                        if [ "$left" -gt 0 ]; then
                          printf 'hash=%s\\nhealth=starting:%s\\n' "$(sed -n 's/^hash=//p' "$C/$id")" "$((left - 1))" > "$C/$id"
                          echo starting
                        else
                          echo healthy
                        fi
                        ;;
                      *) echo "$status" ;;
                    esac
                    ;;
                esac
                ;;
              rm)
                rm -f "$C/${!#}"
                ;;
            esac
            exit 0
            """;

    private static final String FAKE_SLEEP =
            """
            #!/bin/bash
            echo "sleep $*" >> "$FAKE_STATE/calls.log"
            """;

    @Test
    void 옛_복제본을_새_복제본이_준비된_뒤에_하나씩_내린다(@TempDir Path temp) throws IOException, InterruptedException {
        // given
        oldReplicas(temp);

        // when
        Result result = rolling(temp, Map.of(), "api");

        // then
        List<String> calls = result.calls();
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("docker stop"))
                .containsExactly("docker stop -t 40 old1", "docker stop -t 40 old2");
        assertThat(result.calls("docker rm")).containsExactly("docker rm old1", "docker rm old2");
        assertThat(result.calls("docker compose -p gyeongjung up"))
                .containsExactly(UP_SURGE, UP_SURGE);
        assertThat(calls.indexOf(UP_SURGE)).isLessThan(calls.indexOf("docker stop -t 40 old1"));
        assertThat(calls.indexOf("docker rm old1")).isLessThan(calls.lastIndexOf(UP_SURGE));
        assertThat(calls.lastIndexOf(UP_SURGE)).isLessThan(calls.indexOf("docker stop -t 40 old2"));
        assertThat(result.containers()).containsOnly(entry("new1", NEW), entry("new2", NEW));
    }

    @Test
    void 새_복제본이_healthy가_되기_전에는_옛_복제본을_내리지_않는다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 새 복제본이 세 번 확인할 때까지 starting이다
        oldReplicas(temp);
        Map<String, String> env =
                Map.of(
                        "FAKE_NEW_HEALTH", "starting:3",
                        "ROLLING_POLL_SECONDS", "3",
                        "ROLLING_SETTLE_SECONDS", "7");

        // when
        Result result = rolling(temp, env, "api");

        // then
        List<String> calls = result.calls();
        List<String> beforeFirstStop = calls.subList(0, calls.indexOf("docker stop -t 40 old1"));
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(beforeFirstStop.stream().filter("sleep 3"::equals).count())
                .as("healthy가 될 때까지 기다린 횟수")
                .isEqualTo(3);
    }

    @Test
    void 새_복제본이_준비된_뒤_nginx가_새_주소를_찾을_시간을_기다리고_옛_복제본을_내린다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: nginx는 api 주소를 10초마다 다시 찾으므로 기본 대기는 그보다 길어야 한다
        Path withDefault = temp.resolve("default");
        Path withZero = temp.resolve("zero");
        oldReplicas(withDefault);
        oldReplicas(withZero);

        // when
        Result waited = rolling(withDefault, Map.of(), "api");
        Result skipped = rolling(withZero, Map.of("ROLLING_SETTLE_SECONDS", "0"), "api");

        // then
        for (String stop : waited.calls("docker stop")) {
            assertThat(waited.calls().get(waited.calls().indexOf(stop) - 1)).isEqualTo("sleep 12");
        }
        assertThat(waited.calls("sleep")).containsExactly("sleep 12", "sleep 12");
        assertThat(skipped.calls("sleep")).isEmpty();
    }

    @Test
    void 새_복제본이_unhealthy면_옛_복제본을_하나도_내리지_않고_10으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        oldReplicas(temp);

        // when
        Result result = rolling(temp, Map.of("FAKE_NEW_HEALTH", "unhealthy"), "api");

        // then
        assertThat(result.exitCode()).isEqualTo(10);
        assertThat(result.calls("docker stop")).isEmpty();
        assertThat(result.calls("docker rm")).isEmpty();
        assertThat(result.calls("docker compose -p gyeongjung up")).containsExactly(UP_SURGE);
        assertThat(result.containers()).containsOnlyKeys("old1", "old2", "new1");
        assertThat(result.output()).contains("healthy");
        long checks =
                result.calls("docker inspect").stream()
                        .filter(call -> call.contains("Health") && call.endsWith(" new1"))
                        .count();
        assertThat(checks).as("unhealthy는 첫 확인에서 바로 멈춘다").isEqualTo(1);
    }

    @Test
    void 새_복제본이_시간_안에_healthy가_되지_않으면_정해진_횟수만_확인하고_10으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        oldReplicas(temp);
        Map<String, String> env =
                Map.of("FAKE_NEW_HEALTH", "starting:999", "ROLLING_HEALTH_ATTEMPTS", "3");

        // when
        Result result = rolling(temp, env, "api");

        // then
        long checks =
                result.calls("docker inspect").stream()
                        .filter(call -> call.contains("Health") && call.endsWith(" new1"))
                        .count();
        assertThat(result.exitCode()).isEqualTo(10);
        assertThat(checks).as("새 복제본의 상태를 확인한 횟수").isEqualTo(3);
        assertThat(result.calls("docker stop")).isEmpty();
    }

    @Test
    void 새로_만든_복제본이_현재_설정의_해시를_갖지_않으면_옛_복제본을_내리지_않고_10으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 새 복제본이 현재 설정으로 만든 것으로 분류되지 않는다(새 복제본이 하나도 보이지 않는 것과 같다)
        oldReplicas(temp);
        Map<String, String> env =
                Map.of("FAKE_NEW_HASH", "someotherhash", "ROLLING_HEALTH_ATTEMPTS", "3");

        // when
        Result result = rolling(temp, env, "api");

        // then: 확인할 새 복제본이 없다고 곧바로 성공으로 보고 옛 복제본을 내리면 서비스가 끊긴다
        assertThat(result.exitCode()).isEqualTo(10);
        assertThat(result.calls("docker stop")).isEmpty();
        assertThat(result.calls("docker rm")).isEmpty();
    }

    @Test
    void 이미_최신이면_아무것도_바꾸지_않고_0으로_끝난다(@TempDir Path temp) throws IOException, InterruptedException {
        // given
        container(temp, "new1", NEW, "healthy");
        container(temp, "new2", NEW, "healthy");

        // when
        Result result = rolling(temp, Map.of(), "api");

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("docker compose -p gyeongjung up")).isEmpty();
        assertThat(result.calls("docker stop")).isEmpty();
        assertThat(result.calls("docker rm")).isEmpty();
        assertThat(result.containers()).containsOnlyKeys("new1", "new2");
    }

    @Test
    void 복제본이_하나도_없으면_한_번_올리고_healthy를_확인한다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        Path sick = temp.resolve("sick");
        Files.createDirectories(sick);

        // when
        Result first = rolling(temp, Map.of(), "api");
        Result broken = rolling(sick, Map.of("FAKE_NEW_HEALTH", "unhealthy"), "api");

        // then
        assertThat(first.exitCode()).as(first.output()).isZero();
        assertThat(first.calls("docker compose -p gyeongjung up"))
                .containsExactly("docker compose -p gyeongjung up -d --no-build --no-deps api");
        assertThat(first.containers()).containsOnlyKeys("new1", "new2");
        assertThat(broken.exitCode()).isEqualTo(10);
    }

    @Test
    void 중단된_교체는_새_복제본을_더_만들지_않고_남은_옛_복제본만_내린다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 옛 복제본 하나를 내리기 전에 멈춘 상태
        container(temp, "old2", OLD, "healthy");
        container(temp, "new1", NEW, "healthy");
        container(temp, "new2", NEW, "healthy");

        // when
        Result result = rolling(temp, Map.of(), "api");

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("docker stop")).containsExactly("docker stop -t 40 old2");
        assertThat(result.containers()).containsOnlyKeys("new1", "new2");
        assertThat(result.containers().keySet()).doesNotContain("new3");
    }

    @Test
    void 롤백은_같은_스크립트를_이전_설정으로_다시_실행하는_것이고_고장_난_복제본은_기다리지_않는다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 옛 설정으로 돌아가는 중. 고장 난 새 복제본(unhealthy)이 하나 남아 있다
        container(temp, "old1", OLD, "healthy");
        container(temp, "old2", OLD, "healthy");
        container(temp, "new1", NEW, "unhealthy");

        // when
        Result result = rolling(temp, Map.of("FAKE_WANT_HASH", OLD), "api");

        // then
        long waitedOnBroken =
                result.calls("docker inspect").stream()
                        .filter(call -> call.contains("Health") && call.endsWith(" new1"))
                        .count();
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.calls("docker stop")).containsExactly("docker stop -t 40 new1");
        assertThat(result.containers()).containsOnly(entry("old1", OLD), entry("old2", OLD));
        assertThat(waitedOnBroken).as("고장 난 복제본의 상태는 기다릴 이유가 없다").isZero();
    }

    @Test
    void 서비스에_헬스체크가_없으면_아무것도_바꾸지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        oldReplicas(temp);

        // when
        Result result = rolling(temp, Map.of("FAKE_NO_HEALTHCHECK", "1"), "api");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.calls("docker compose -p gyeongjung up")).isEmpty();
        assertThat(result.calls("docker stop")).isEmpty();
        assertThat(result.output()).contains("헬스체크");
    }

    @Test
    void 서비스_이름이_올바르지_않으면_아무것도_부르지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        List<String> names = List.of("", "API", "api;rm -rf", "-x", "../api", "a b");

        // when
        List<String> accepted = new ArrayList<>();
        int i = 0;
        for (String name : names) {
            Result result = rolling(temp.resolve("n" + i++), Map.of(), name);
            if (result.exitCode() != 2 || !result.calls().isEmpty()) {
                accepted.add("[" + name + "] 종료 코드 " + result.exitCode());
            }
        }

        // then
        assertThat(accepted).as("받아들이거나 docker를 부른 이름").isEmpty();
    }

    @Test
    void compose에_없는_서비스면_아무것도_바꾸지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        oldReplicas(temp);

        // when
        Result result = rolling(temp, Map.of("FAKE_NO_SERVICE", "1"), "api");

        // then
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.calls("docker compose -p gyeongjung up")).isEmpty();
        assertThat(result.calls("docker stop")).isEmpty();
    }

    @Test
    void 서비스_전체를_지우거나_강제로_다시_만드는_명령은_쓰지_않는다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        oldReplicas(temp);

        // when
        Result result = rolling(temp, Map.of(), "api");

        // then: 복제본은 이름을 정해서 하나씩만 내린다
        List<String> dangerous =
                result.calls().stream().filter(call -> DANGEROUS.matcher(call).find()).toList();
        assertThat(result.exitCode()).isZero();
        assertThat(dangerous).as("서비스 전체를 내리거나 강제로 만드는 명령").isEmpty();
        assertThat(result.calls("docker rm")).allMatch(call -> call.split(" ").length == 3);
    }

    @Test
    void 프로젝트_이름은_환경변수를_따르고_없으면_gyeongjung이다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        Path custom = temp.resolve("custom");
        Path fallback = temp.resolve("fallback");
        container(custom, "new1", NEW, "healthy");
        container(fallback, "new1", NEW, "healthy");

        // when
        Result named =
                rolling(custom, Map.of("COMPOSE_PROJECT_NAME", "gyeongjung-rolling-test"), "api");
        Result unnamed = rolling(fallback, Map.of(), "api");

        // then
        assertThat(named.calls("docker compose"))
                .isNotEmpty()
                .allMatch(call -> call.startsWith("docker compose -p gyeongjung-rolling-test "));
        assertThat(unnamed.calls("docker compose"))
                .isNotEmpty()
                .allMatch(call -> call.startsWith("docker compose -p gyeongjung "));
    }

    private record Result(
            int exitCode, String output, List<String> calls, Map<String, String> containers) {
        List<String> calls(String prefix) {
            return calls.stream().filter(call -> call.startsWith(prefix)).toList();
        }
    }

    private static void oldReplicas(Path dir) throws IOException {
        container(dir, "old1", OLD, "healthy");
        container(dir, "old2", OLD, "healthy");
    }

    private static void container(Path dir, String id, String hash, String health)
            throws IOException {
        Path containers = Files.createDirectories(dir.resolve("state/containers"));
        Files.writeString(containers.resolve(id), "hash=" + hash + "\nhealth=" + health + "\n");
    }

    private static Result rolling(Path dir, Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        Path state = prepareFakes(dir);

        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(RepositoryFiles.root().resolve("ops/rolling.sh").toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        configure(builder.environment(), dir, env);

        ProcessOutput run = ProcessOutput.run(builder, 60);
        assertThat(run.finished()).as("rolling.sh가 60초 안에 끝나야 한다").isTrue();
        return new Result(
                run.exitCode(),
                run.output(),
                Files.readAllLines(state.resolve("calls.log")),
                containersOf(state.resolve("containers")));
    }

    private static Path prepareFakes(Path dir) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path state = Files.createDirectories(dir.resolve("state"));
        Files.createDirectories(state.resolve("containers"));
        if (!Files.exists(state.resolve("calls.log"))) {
            Files.createFile(state.resolve("calls.log"));
        }
        install(bin.resolve("docker"), FAKE_DOCKER);
        install(bin.resolve("sleep"), FAKE_SLEEP);
        return state;
    }

    private static void configure(Map<String, String> env, Path dir, Map<String, String> scenario) {
        // 개발자의 셸에 설정된 값이 시험 결과를 바꾸지 않게 지운다.
        env.keySet()
                .removeIf(name ->
                        name.startsWith("COMPOSE_")
                                || name.startsWith("FAKE_")
                                || name.startsWith("ROLLING_"));
        env.put("PATH", dir.resolve("bin") + File.pathSeparator + System.getenv("PATH"));
        env.put("FAKE_STATE", dir.resolve("state").toString());
        env.putAll(scenario);
    }

    private static Map<String, String> containersOf(Path containers) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> files = Files.list(containers)) {
            for (Path file : files.toList()) {
                String hash =
                        Files.readAllLines(file).stream()
                                .filter(line -> line.startsWith("hash="))
                                .findFirst()
                                .orElse("hash=")
                                .substring("hash=".length());
                result.put(file.getFileName().toString(), hash);
            }
        }
        return result;
    }

    private static void install(Path file, String script) throws IOException {
        Files.writeString(file, script);
        assertThat(file.toFile().setExecutable(true))
                .as("가짜 %s를 실행 가능하게 만든다", file.getFileName())
                .isTrue();
    }
}
