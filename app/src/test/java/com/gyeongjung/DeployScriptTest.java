package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 배포 스크립트의 약속을 지킨다. 서버 없이 docker, 스모크, 이미지 정리를 가짜로 바꿔 끼워서
// "무엇을 어떤 순서로 부르고 어떤 종료 코드로 끝나는지"만 확인한다. 진짜 docker로 도는 것은 로컬 시험으로 따로 확인한다.
class DeployScriptTest {

    private static final String REGISTRY = "ghcr.io/example";
    private static final String OLD = "1111111";
    private static final String NEW = "2222222";

    // 실행 중인 이미지와 호출 기록을 FAKE_STATE 폴더에 둔다. 서비스 이름은 "compose ps -q <서비스>"의 마지막 인자다.
    // 알 수 없는 명령은 성공으로 돌려준다. 순서를 확인하는 사건(events)은 백업, 이미지 받기, 교체, 이미지 삭제만 기록한다.
    private static final String FAKE_DOCKER =
            """
            #!/bin/bash
            echo "docker $*" >> "$FAKE_STATE/calls.log"
            state() { cat "$FAKE_STATE/$1" 2>/dev/null; }
            case "$1" in
              compose)
                shift
                if [ "$1" = "-p" ]; then shift 2; fi
                case "$1" in
                  ps)
                    case "$3" in
                      worker) [ -n "$(state running_app)" ] && echo worker-cid ;;
                      backup) [ -n "$(state running_backup)" ] && echo backup-cid ;;
                      mysql) [ -n "$(state running_mysql)" ] && echo mysql-cid ;;
                    esac
                    exit 0
                    ;;
                  config)
                    [ -n "${FAKE_CONFIG_FAIL:-}" ] && exit 1
                    for s in ${FAKE_SERVICES:-}; do echo "$s"; done
                    exit 0
                    ;;
                  exec)
                    [ -n "${FAKE_BACKUP_FAIL:-}" ] && exit 1
                    echo "backup-run" >> "$FAKE_STATE/events.log"
                    exit 0
                    ;;
                  up)
                    echo "up APP_IMAGE=${APP_IMAGE:-} BACKUP_IMAGE=${BACKUP_IMAGE:-}" >> "$FAKE_STATE/events.log"
                    [ "${APP_IMAGE:-}" = "${FAKE_UP_FAIL_IMAGE:-none}" ] && exit 1
                    printf '%s' "${APP_IMAGE:-}" > "$FAKE_STATE/running_app"
                    printf '%s' "${BACKUP_IMAGE:-}" > "$FAKE_STATE/running_backup"
                    printf 'running' > "$FAKE_STATE/running_mysql"
                    exit 0
                    ;;
                esac
                ;;
              inspect)
                case "$3" in
                  *Config.Image*)
                    if [ "$4" = "worker-cid" ]; then state running_app; else state running_backup; fi
                    ;;
                  *Health.Status*)
                    if [ "$(state running_app)" = "${FAKE_HEALTH_BAD_IMAGE:-none}" ]; then
                      echo "${FAKE_HEALTH_BAD_STATUS:-unhealthy}"
                    else
                      echo healthy
                    fi
                    ;;
                esac
                exit 0
                ;;
              pull)
                echo "pull $2" >> "$FAKE_STATE/events.log"
                case "$2" in *"${FAKE_PULL_FAIL:-no-such-fail}"*) exit 1 ;; esac
                exit 0
                ;;
              rmi)
                echo "$*" >> "$FAKE_STATE/events.log"
                exit 0
                ;;
            esac
            exit 0
            """;

    // 지금 실행 중인 앱 이미지가 FAKE_SMOKE_FAIL_IMAGE이면 실패한다. FAKE_SMOKE_FAIL_TIMES번만 실패하게 할 수도 있다.
    private static final String FAKE_SMOKE =
            """
            #!/bin/bash
            image=$(cat "$FAKE_STATE/running_app")
            echo "smoke COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-} image=$image" >> "$FAKE_STATE/events.log"
            if [ "$image" = "${FAKE_SMOKE_FAIL_IMAGE:-none}" ]; then
              count=$(( $(cat "$FAKE_STATE/smoke_fail_count" 2>/dev/null || echo 0) + 1 ))
              echo "$count" > "$FAKE_STATE/smoke_fail_count"
              if [ -z "${FAKE_SMOKE_FAIL_TIMES:-}" ] || [ "$count" -le "$FAKE_SMOKE_FAIL_TIMES" ]; then exit 1; fi
            fi
            exit 0
            """;

    private static final String FAKE_PRUNE =
            """
            #!/bin/bash
            echo "prune" >> "$FAKE_STATE/events.log"
            exit "${FAKE_PRUNE_EXIT:-0}"
            """;

    // 롤링이 어떤 이미지로, 어떤 서비스에 대해 불렸는지 남긴다. 지금 실행하려는 앱 이미지가 FAKE_ROLLING_FAIL_IMAGE이면 실패(10)한다.
    private static final String FAKE_ROLLING =
            """
            #!/bin/bash
            echo "rolling service=$1 APP_IMAGE=${APP_IMAGE:-} BACKUP_IMAGE=${BACKUP_IMAGE:-} COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-}" >> "$FAKE_STATE/events.log"
            [ "${APP_IMAGE:-}" = "${FAKE_ROLLING_FAIL_IMAGE:-none}" ] && exit 10
            exit 0
            """;

    private static final String API_SERVICES = "mysql worker api backup nginx";

    @Test
    void 배포가_성공하면_이미지받기_백업_교체_스모크_정리_순서로_하고_0으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of(), NEW);

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW),
                        pull("backup", NEW),
                        "backup-run",
                        up(NEW),
                        smokeOn(NEW),
                        "prune");
    }

    @Test
    void 첫_배포는_백업을_건너뛰고_성공하면_0으로_끝난다(@TempDir Path temp) throws IOException, InterruptedException {
        // when
        Result result = deploy(temp, Map.of(), NEW);

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW), pull("backup", NEW), up(NEW), smokeOn(NEW), "prune");
    }

    @Test
    void 배포_직전_백업이_실패하면_아무것도_바꾸지_않고_10으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of("FAKE_BACKUP_FAIL", "1"), NEW);

        // then
        assertThat(result.exitCode()).isEqualTo(10);
        // 이미지는 받아 두지만 컨테이너는 바꾸지 않는다.
        assertThat(result.events()).containsExactly(pull("server", NEW), pull("backup", NEW));
    }

    @Test
    void 이미지를_받지_못하면_백업도_컨테이너도_건드리지_않고_10으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of("FAKE_PULL_FAIL", NEW), NEW);

        // then
        assertThat(result.exitCode()).isEqualTo(10);
        assertThat(result.events()).containsExactly(pull("server", NEW));
    }

    @Test
    void 새_버전이_헬스체크에_실패하면_이전_이미지로_되돌리고_20으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of("FAKE_HEALTH_BAD_IMAGE", app(NEW)), NEW);

        // then
        assertThat(result.exitCode()).isEqualTo(20);
        // 새 버전은 헬스체크에서 걸러져 스모크까지 가지 않고, 되돌린 뒤에 스모크로 확인한다.
        // 실패한 이미지는 지우고(남길 자리를 차지하지 않게), 정리는 하지 않는다.
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW),
                        pull("backup", NEW),
                        "backup-run",
                        up(NEW),
                        up(OLD),
                        smokeOn(OLD),
                        rmiApp(NEW),
                        rmiBackup(NEW));
    }

    @Test
    void 새_버전이_계속_준비되지_않아도_시간이_지나면_이전_이미지로_되돌린다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);
        Map<String, String> scenario =
                Map.of("FAKE_HEALTH_BAD_IMAGE", app(NEW), "FAKE_HEALTH_BAD_STATUS", "starting");

        // when
        Result result = deploy(temp, scenario, NEW);

        // then
        assertThat(result.exitCode()).isEqualTo(20);
        assertThat(result.events()).contains(up(NEW), up(OLD), rmiApp(NEW)).doesNotContain("prune");
    }

    @Test
    void 스모크가_계속_실패하면_이전_이미지로_되돌리고_20으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of("FAKE_SMOKE_FAIL_IMAGE", app(NEW)), NEW);

        // then
        assertThat(result.exitCode()).isEqualTo(20);
        // 스모크는 3번(SMOKE_ATTEMPTS) 시도하고, 되돌린 뒤에 이전 버전으로 한 번 더 확인한다. 실패한 이미지는 지운다.
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW),
                        pull("backup", NEW),
                        "backup-run",
                        up(NEW),
                        smokeOn(NEW),
                        smokeOn(NEW),
                        smokeOn(NEW),
                        up(OLD),
                        smokeOn(OLD),
                        rmiApp(NEW),
                        rmiBackup(NEW));
    }

    @Test
    void 스모크가_일시적으로_실패해도_재시도해서_통과하면_되돌리지_않는다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: Prometheus가 새 worker를 다시 긁기 전에는 처음 두 번이 실패한다
        runningOn(temp, OLD);
        Map<String, String> scenario =
                Map.of("FAKE_SMOKE_FAIL_IMAGE", app(NEW), "FAKE_SMOKE_FAIL_TIMES", "2");

        // when
        Result result = deploy(temp, scenario, NEW);

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW),
                        pull("backup", NEW),
                        "backup-run",
                        up(NEW),
                        smokeOn(NEW),
                        smokeOn(NEW),
                        smokeOn(NEW),
                        "prune");
    }

    @Test
    void 첫_배포가_실패하면_컨테이너를_그대로_두고_30으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result result = deploy(temp, Map.of("FAKE_HEALTH_BAD_IMAGE", app(NEW)), NEW);

        // then
        assertThat(result.exitCode()).isEqualTo(30);
        // 되돌릴 이전 버전이 없어서 다시 교체하지 않는다. 원인을 조사할 수 있게 내리지도 않는다.
        assertThat(result.events())
                .containsExactly(pull("server", NEW), pull("backup", NEW), up(NEW));
        assertThat(result.output()).contains("되돌릴 이전 버전이 없다");
    }

    @Test
    void 되돌림도_실패하면_30으로_끝난다(@TempDir Path temp) throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);
        Map<String, String> scenario =
                Map.of("FAKE_HEALTH_BAD_IMAGE", app(NEW), "FAKE_UP_FAIL_IMAGE", app(OLD));

        // when
        Result result = deploy(temp, scenario, NEW);

        // then
        assertThat(result.exitCode()).isEqualTo(30);
        // 되돌림도 실패했으므로 실패한 이미지를 지우지 않는다. 원인을 조사할 수 있게 둔다.
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW), pull("backup", NEW), "backup-run", up(NEW), up(OLD));
    }

    @Test
    void 정리가_실패해도_배포는_성공으로_끝난다(@TempDir Path temp) throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of("FAKE_PRUNE_EXIT", "1"), NEW);

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.events()).endsWith("prune");
    }

    @Test
    void compose는_항상_프로젝트_이름을_고정해서_부르고_하위_스크립트에도_넘긴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of(), NEW);

        // then
        List<String> composeCalls =
                result.calls().stream().filter(call -> call.startsWith("docker compose")).toList();
        assertThat(composeCalls)
                .isNotEmpty()
                .allMatch(call -> call.startsWith("docker compose -p gyeongjung "));
        // 스모크 스크립트는 프로젝트를 고정하지 않아서 환경변수로 같은 프로젝트를 보게 해야 한다.
        assertThat(result.events()).contains(smokeOn(NEW));
    }

    @Test
    void 입력이_올바르지_않으면_아무것도_실행하지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        List<String> tags =
                List.of("latest", "abc", "ZZZZZZZ", "ABCDEF1", "1234567;id", "$(id)", "");

        // when
        List<String> accepted = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            Result result = deploy(temp.resolve("tag" + i), Map.of(), tags.get(i));
            if (result.exitCode() != 2 || !result.calls().isEmpty()) {
                accepted.add(tags.get(i) + " -> 종료 코드 " + result.exitCode());
            }
        }
        Result noTag = deploy(temp.resolve("none"), Map.of());
        Result noRegistry = deploy(temp.resolve("registry"), Map.of("IMAGE_REGISTRY", ""), NEW);

        // then
        assertThat(accepted).as("잘못된 태그를 받아들인 경우").isEmpty();
        assertThat(noTag.exitCode()).isEqualTo(2);
        assertThat(noRegistry.exitCode()).isEqualTo(2);
        assertThat(noRegistry.calls()).isEmpty();
    }

    @Test
    void 서버에서는_이미지를_빌드하지_않는다(@TempDir Path temp) throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of(), NEW);

        // then
        // 서버에서 빌드하면 같은 서버의 다른 서비스의 CPU와 메모리를 빼앗는다.
        List<String> upCalls =
                result.calls().stream().filter(call -> call.contains(" up ")).toList();
        assertThat(upCalls).isNotEmpty().allMatch(call -> call.contains("--no-build"));
    }

    @Test
    void 같은_태그를_다시_배포해_되돌려도_실행_중인_이미지는_지우지_않는다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given: 이미 OLD가 실행 중인데 같은 OLD를 다시 배포하고, 스모크가 처음 3번(SMOKE_ATTEMPTS) 실패한다
        runningOn(temp, OLD);
        Map<String, String> scenario =
                Map.of("FAKE_SMOKE_FAIL_IMAGE", app(OLD), "FAKE_SMOKE_FAIL_TIMES", "3");

        // when
        Result result = deploy(temp, scenario, OLD);

        // then
        assertThat(result.exitCode()).isEqualTo(20);
        // 새로 배포한 이미지와 되돌린 이미지가 같으므로 지우면 지금 쓰는 이미지를 지우게 된다.
        assertThat(result.events()).noneMatch(event -> event.startsWith("rmi"));
    }

    @Test
    void api가_없으면_롤링을_부르지_않고_모든_서비스를_한꺼번에_올린다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of("FAKE_SERVICES", "mysql worker backup"), NEW);

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.calls())
                .contains(
                        "docker compose -p gyeongjung config --services",
                        "docker compose -p gyeongjung up -d --no-build");
        assertThat(result.events()).noneMatch(event -> event.startsWith("rolling"));
    }

    @Test
    void api가_있으면_일괄_교체에서_api를_빼고_나머지_서비스만_올린다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of("FAKE_SERVICES", API_SERVICES), NEW);

        // then: compose가 api까지 한꺼번에 교체하면 롤링이 의미가 없다
        List<String> upCalls =
                result.calls().stream().filter(call -> call.contains(" up ")).toList();
        assertThat(result.exitCode()).isZero();
        assertThat(upCalls)
                .containsExactly(
                        "docker compose -p gyeongjung up -d --no-build mysql worker backup nginx");
    }

    @Test
    void api가_있으면_worker가_올라온_뒤에_api를_롤링하고_그_뒤에_스모크를_돈다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of("FAKE_SERVICES", API_SERVICES), NEW);

        // then: Flyway는 worker가 먼저 실행하고, api는 이미 바뀐 스키마를 본다
        assertThat(result.exitCode()).isZero();
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW),
                        pull("backup", NEW),
                        "backup-run",
                        up(NEW),
                        rolling(NEW),
                        smokeOn(NEW),
                        "prune");
    }

    @Test
    void worker가_healthy가_되지_않으면_새_이미지로_api를_롤링하지_않는다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);
        Map<String, String> scenario =
                Map.of("FAKE_SERVICES", API_SERVICES, "FAKE_HEALTH_BAD_IMAGE", app(NEW));

        // when
        Result result = deploy(temp, scenario, NEW);

        // then: worker가 먼저 검증을 통과해야 api를 건드린다. 되돌릴 때는 api도 이전 이미지로 맞춘다.
        assertThat(result.exitCode()).isEqualTo(20);
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW),
                        pull("backup", NEW),
                        "backup-run",
                        up(NEW),
                        up(OLD),
                        rolling(OLD),
                        smokeOn(OLD),
                        rmiApp(NEW),
                        rmiBackup(NEW));
    }

    @Test
    void api_롤링이_실패하면_worker와_api를_이전_이미지로_되돌리고_20으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);
        Map<String, String> scenario =
                Map.of("FAKE_SERVICES", API_SERVICES, "FAKE_ROLLING_FAIL_IMAGE", app(NEW));

        // when
        Result result = deploy(temp, scenario, NEW);

        // then: 롤링이 실패하면 새 버전의 스모크까지 가지 않는다
        assertThat(result.exitCode()).isEqualTo(20);
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW),
                        pull("backup", NEW),
                        "backup-run",
                        up(NEW),
                        rolling(NEW),
                        up(OLD),
                        rolling(OLD),
                        smokeOn(OLD),
                        rmiApp(NEW),
                        rmiBackup(NEW));
    }

    @Test
    void api_롤링이_실패한_첫_배포는_컨테이너를_그대로_두고_30으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        Map<String, String> scenario =
                Map.of("FAKE_SERVICES", API_SERVICES, "FAKE_ROLLING_FAIL_IMAGE", app(NEW));

        // when
        Result result = deploy(temp, scenario, NEW);

        // then
        assertThat(result.exitCode()).isEqualTo(30);
        assertThat(result.events())
                .containsExactly(pull("server", NEW), pull("backup", NEW), up(NEW), rolling(NEW));
        assertThat(result.output()).contains("되돌릴 이전 버전이 없다");
    }

    @Test
    void 스모크가_실패해_되돌릴_때도_api를_이전_이미지로_롤링한다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);
        Map<String, String> scenario =
                Map.of("FAKE_SERVICES", API_SERVICES, "FAKE_SMOKE_FAIL_IMAGE", app(NEW));

        // when
        Result result = deploy(temp, scenario, NEW);

        // then
        assertThat(result.exitCode()).isEqualTo(20);
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW),
                        pull("backup", NEW),
                        "backup-run",
                        up(NEW),
                        rolling(NEW),
                        smokeOn(NEW),
                        smokeOn(NEW),
                        smokeOn(NEW),
                        up(OLD),
                        rolling(OLD),
                        smokeOn(OLD),
                        rmiApp(NEW),
                        rmiBackup(NEW));
    }

    @Test
    void 되돌리는_중_api_롤링도_실패하면_실패한_이미지를_지우지_않고_30으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);
        Map<String, String> scenario =
                Map.of(
                        "FAKE_SERVICES", API_SERVICES,
                        "FAKE_HEALTH_BAD_IMAGE", app(NEW),
                        "FAKE_ROLLING_FAIL_IMAGE", app(OLD));

        // when
        Result result = deploy(temp, scenario, NEW);

        // then: 되돌림이 실패했으니 원인을 조사할 수 있게 실패한 이미지를 둔다
        assertThat(result.exitCode()).isEqualTo(30);
        assertThat(result.events())
                .containsExactly(
                        pull("server", NEW),
                        pull("backup", NEW),
                        "backup-run",
                        up(NEW),
                        up(OLD),
                        rolling(OLD));
    }

    @Test
    void compose_설정을_읽지_못하면_이미지를_받기_전에_10으로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        runningOn(temp, OLD);

        // when
        Result result = deploy(temp, Map.of("FAKE_CONFIG_FAIL", "1"), NEW);

        // then: api가 있는지 모르면 롤링해야 할지 정할 수 없으므로 아무것도 바꾸지 않고 멈춘다
        assertThat(result.exitCode()).isEqualTo(10);
        assertThat(result.events()).isEmpty();
    }

    private static String app(String tag) {
        return REGISTRY + "/gyeongjung-server:" + tag;
    }

    private static String backup(String tag) {
        return REGISTRY + "/gyeongjung-backup:" + tag;
    }

    private static String rmiApp(String tag) {
        return "rmi " + app(tag);
    }

    private static String rmiBackup(String tag) {
        return "rmi " + backup(tag);
    }

    private static String pull(String name, String tag) {
        return "pull " + REGISTRY + "/gyeongjung-" + name + ":" + tag;
    }

    private static String up(String tag) {
        return "up APP_IMAGE=" + app(tag) + " BACKUP_IMAGE=" + backup(tag);
    }

    private static String smokeOn(String tag) {
        return "smoke COMPOSE_PROJECT_NAME=gyeongjung image=" + app(tag);
    }

    private static String rolling(String tag) {
        return "rolling service=api APP_IMAGE="
                + app(tag)
                + " BACKUP_IMAGE="
                + backup(tag)
                + " COMPOSE_PROJECT_NAME=gyeongjung";
    }

    // 이미 해당 태그가 배포되어 mysql, worker, backup이 떠 있는 상태로 만든다.
    private static void runningOn(Path temp, String tag) throws IOException {
        Path state = Files.createDirectories(temp.resolve("state"));
        Files.writeString(state.resolve("running_app"), app(tag));
        Files.writeString(state.resolve("running_backup"), backup(tag));
        Files.writeString(state.resolve("running_mysql"), "running");
    }

    private record Result(int exitCode, String output, List<String> events, List<String> calls) {}

    private static Result deploy(Path temp, Map<String, String> scenario, String... args)
            throws IOException, InterruptedException {
        Path state = prepareFakes(temp);

        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(RepositoryFiles.root().resolve("ops/deploy.sh").toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        configure(builder.environment(), temp, scenario);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("deploy.sh가 60초 안에 끝나야 한다").isTrue();
        return new Result(
                process.exitValue(),
                output,
                Files.readAllLines(state.resolve("events.log")),
                Files.readAllLines(state.resolve("calls.log")));
    }

    // 호출 기록 파일과 가짜 docker, 스모크, 정리 스크립트를 만들고 상태 폴더를 돌려준다.
    private static Path prepareFakes(Path temp) throws IOException {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path state = Files.createDirectories(temp.resolve("state"));
        Files.createFile(state.resolve("events.log"));
        Files.createFile(state.resolve("calls.log"));
        install(bin.resolve("docker"), FAKE_DOCKER);
        install(temp.resolve("smoke.sh"), FAKE_SMOKE);
        install(temp.resolve("prune.sh"), FAKE_PRUNE);
        install(temp.resolve("rolling.sh"), FAKE_ROLLING);
        return state;
    }

    private static void configure(
            Map<String, String> env, Path temp, Map<String, String> scenario) {
        // 개발자의 셸에 설정된 값이 시험 결과를 바꾸지 않게 지운다.
        env.keySet().removeIf(name -> name.startsWith("COMPOSE_") || name.startsWith("FAKE_"));
        env.put("PATH", temp.resolve("bin") + File.pathSeparator + System.getenv("PATH"));
        env.put("FAKE_STATE", temp.resolve("state").toString());
        env.put("IMAGE_REGISTRY", REGISTRY);
        env.put("DEPLOY_POLL_SECONDS", "0");
        env.put("HEALTH_ATTEMPTS", "3");
        env.put("SMOKE_ATTEMPTS", "3");
        env.put("SMOKE_COMMAND", temp.resolve("smoke.sh").toString());
        env.put("PRUNE_COMMAND", temp.resolve("prune.sh").toString());
        env.put("ROLLING_COMMAND", temp.resolve("rolling.sh").toString());
        env.putAll(scenario);
    }

    private static void install(Path file, String script) throws IOException {
        Files.writeString(file, script);
        assertThat(file.toFile().setExecutable(true))
                .as("가짜 %s를 실행 가능하게 만든다", file.getFileName())
                .isTrue();
    }
}
