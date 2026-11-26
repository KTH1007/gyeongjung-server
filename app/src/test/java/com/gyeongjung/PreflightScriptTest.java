package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 서버에서 배포 전에 돌리는 읽기 전용 사전 점검의 약속을 지킨다. docker, curl, df를 가짜로 바꿔 끼우고,
// 포트는 진짜 소켓으로 시험한다. 서버의 실제 상태는 서버에서 돌려 확인한다.
class PreflightScriptTest {

    private static final String REGISTRY = "ghcr.io/example";
    private static final String NEIGHBOR = "http://neighbor.test/actuator/health";
    private static final Pattern WRITE_WORDS =
            Pattern.compile(
                    "\\b(up|down|rm|rmi|pull|push|run|exec|stop|start|restart|build|create|kill|prune|volume|network)\\b");
    private static final String VALID_ENV =
            """
            COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml
            DB_USERNAME=gyeongjung
            DB_PASSWORD=secret
            GRAFANA_ADMIN_PASSWORD=secret
            """;

    // 호출 기록은 FAKE_STATE/calls.log에 남긴다. FAKE_* 값으로 상황을 바꾼다.
    private static final String FAKE_DOCKER =
            """
            #!/bin/bash
            echo "docker $*" >> "$FAKE_STATE/calls.log"
            case "$1" in
              info)
                if [ -n "${FAKE_DOCKER_INFO_FAIL:-}" ]; then echo "Cannot connect to the Docker daemon" >&2; exit 1; fi
                echo /var/lib/docker
                ;;
              compose)
                shift
                if [ -n "${FAKE_COMPOSE_MISSING:-}" ]; then echo "unknown shorthand flag: 'p' in -p" >&2; exit 125; fi
                if [ "$1" = "-p" ]; then shift 2; fi
                case "$1" in
                  version)
                    echo "${FAKE_COMPOSE_VERSION:-v2.29.1}"
                    ;;
                  config)
                    if [ -n "${FAKE_CONFIG_FAIL:-}" ]; then echo "required variable DB_PASSWORD is missing a value" >&2; exit 1; fi
                    ;;
                  ps)
                    [ -n "${FAKE_RUNNING:-}" ] && echo some-cid
                    ;;
                esac
                ;;
              manifest)
                case "$3" in *"${FAKE_MANIFEST_FAIL:-no-such-fail}"*) echo "no such manifest: $3" >&2; exit 1 ;; esac
                ;;
            esac
            exit 0
            """;

    private static final String FAKE_CURL =
            """
            #!/bin/bash
            echo "curl $*" >> "$FAKE_STATE/calls.log"
            case "$*" in
              *neighbor.test*) printf '%s' "${FAKE_NEIGHBOR_STATUS:-200}" ;;
              *)
                [ -n "${FAKE_REGISTRY_UNREACHABLE:-}" ] && exit 7
                printf '%s' "${FAKE_REGISTRY_STATUS:-401}"
                ;;
            esac
            """;

    private static final String FAKE_DF =
            """
            #!/bin/bash
            echo "df $*" >> "$FAKE_STATE/calls.log"
            echo "Filesystem 1024-blocks Used Available Capacity Mounted on"
            echo "/dev/sda1 47185920 19922944 ${FAKE_DF_AVAIL_KB:-27262976} 43% /"
            """;

    @Test
    void 모든_조건이_맞으면_통과하고_0으로_끝난다(@TempDir Path temp) throws IOException, InterruptedException {
        // when
        Result result = preflight(temp, Map.of(), "2222222");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.failures()).isEmpty();
        assertThat(result.output()).contains("사전 점검 통과");
    }

    @Test
    void 읽기_전용_명령만_부른다(@TempDir Path temp) throws IOException, InterruptedException {
        // when
        Result result = preflight(temp, Map.of("NEIGHBOR_HEALTH_URL", NEIGHBOR), "2222222");

        // then
        List<String> writes =
                result.calls().stream().filter(call -> WRITE_WORDS.matcher(call).find()).toList();
        assertThat(result.calls()).as("점검이 명령을 불러야 한다").isNotEmpty();
        assertThat(writes).as("서버 상태를 바꿀 수 있는 명령").isEmpty();
    }

    @Test
    void Compose가_2_24_미만이거나_없으면_실패한다(@TempDir Path temp) throws IOException, InterruptedException {
        // given: "2.9.0"을 문자열로 비교하면 "2.24"보다 크게 나온다
        List<String> tooOld = List.of("2.23.9", "v2.9.0", "1.29.2");
        List<String> enough = List.of("2.24.0", "v2.24.5", "2.40.1", "v5.1.1");

        // when
        List<String> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (int i = 0; i < tooOld.size(); i++) {
            Result result =
                    preflight(
                            temp.resolve("old" + i), Map.of("FAKE_COMPOSE_VERSION", tooOld.get(i)));
            if (!hasFailure(result, "Compose")) {
                accepted.add(tooOld.get(i));
            }
        }
        for (int i = 0; i < enough.size(); i++) {
            Result result =
                    preflight(
                            temp.resolve("ok" + i), Map.of("FAKE_COMPOSE_VERSION", enough.get(i)));
            if (hasFailure(result, "Compose")) {
                rejected.add(enough.get(i));
            }
        }
        Result missing = preflight(temp.resolve("missing"), Map.of("FAKE_COMPOSE_MISSING", "1"));

        // then
        assertThat(accepted).as("너무 낮은데 통과한 버전").isEmpty();
        assertThat(rejected).as("충분한데 실패한 버전").isEmpty();
        assertThat(hasFailure(missing, "Compose")).isTrue();
    }

    @Test
    void Compose가_없으면_compose_설정은_점검하지_않고_건너뛴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when: 진짜 docker는 compose 플러그인이 없으면 모든 compose 명령이 실패한다
        Result result = preflight(temp, Map.of("FAKE_COMPOSE_MISSING", "1"));

        // then: 같은 원인으로 실패가 둘 나오면 진짜 문제를 찾기 어렵다
        assertThat(result.failures()).hasSize(1);
        assertThat(hasFailure(result, "Compose")).isTrue();
        assertThat(result.output()).contains("SKIP compose 설정");
    }

    @Test
    void Docker_데몬에_접근하지_못하면_실패한다(@TempDir Path temp) throws IOException, InterruptedException {
        // when
        Result result = preflight(temp, Map.of("FAKE_DOCKER_INFO_FAIL", "1"));

        // then
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(hasFailure(result, "Docker")).isTrue();
    }

    @Test
    void 포트가_이미_쓰이고_있으면_실패하고_우리_스택이_이미_떠_있으면_건너뛴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        try (ServerSocket busy = new ServerSocket(0)) {
            // given
            Map<String, String> occupied =
                    Map.of("PREFLIGHT_PORTS", String.valueOf(busy.getLocalPort()));
            Map<String, String> ours = new LinkedHashMap<>(occupied);
            ours.put("FAKE_RUNNING", "1");

            // when
            Result conflict = preflight(temp.resolve("conflict"), occupied);
            Result redeploy = preflight(temp.resolve("redeploy"), ours);

            // then
            assertThat(hasFailure(conflict, "포트")).isTrue();
            assertThat(hasFailure(redeploy, "포트")).isFalse();
            assertThat(redeploy.output()).contains("SKIP 포트");
        }
    }

    @Test
    void 디스크_여유가_기준_미만이면_실패한다(@TempDir Path temp) throws IOException, InterruptedException {
        // when: 기본 기준은 8GB(8388608KB)다. 단위 변환 실수를 잡으려고 경계 값으로 본다
        Result enough = preflight(temp.resolve("enough"), Map.of("FAKE_DF_AVAIL_KB", "8388608"));
        Result less = preflight(temp.resolve("less"), Map.of("FAKE_DF_AVAIL_KB", "8388607"));

        // then
        assertThat(hasFailure(enough, "디스크")).isFalse();
        assertThat(hasFailure(less, "디스크")).isTrue();
    }

    @Test
    void 가용_메모리가_기준_미만이면_실패한다(@TempDir Path temp) throws IOException, InterruptedException {
        // when: 기본 기준은 4096MB(4194304KB)다
        Result enough =
                preflight(
                        temp.resolve("enough"),
                        Map.of("MEMINFO_FILE", meminfo(temp.resolve("m1"), 4_194_304)));
        Result less =
                preflight(
                        temp.resolve("less"),
                        Map.of("MEMINFO_FILE", meminfo(temp.resolve("m2"), 4_194_303)));

        // then
        assertThat(hasFailure(enough, "메모리")).isFalse();
        assertThat(hasFailure(less, "메모리")).isTrue();
    }

    @Test
    void 서버_env가_잘못되면_실패한다(@TempDir Path temp) throws IOException, InterruptedException {
        // given
        Map<String, Path> broken = brokenEnvFiles(temp);

        // when
        List<String> accepted = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, Path> entry : broken.entrySet()) {
            Result result =
                    preflight(
                            temp.resolve("run" + i++),
                            Map.of("ENV_FILE", entry.getValue().toString()));
            if (!hasFailure(result, ".env")) {
                accepted.add(entry.getKey());
            }
        }

        // then
        assertThat(accepted).as("잘못된 .env를 통과시킨 경우").isEmpty();
    }

    @Test
    void compose_설정이_잘못되면_실패하고_이유를_보여준다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result result = preflight(temp, Map.of("FAKE_CONFIG_FAIL", "1"));

        // then
        assertThat(hasFailure(result, "compose 설정")).isTrue();
        assertThat(result.output()).contains("DB_PASSWORD");
    }

    @Test
    void 레지스트리에_닿지_못하거나_이상한_응답이면_실패한다(@TempDir Path temp) throws IOException, InterruptedException {
        // given: 인증 전에는 401이 정상이다
        List<String> fine = List.of("200", "401");
        List<String> bad = List.of("500", "404", "000");

        // when
        List<String> wrong = new ArrayList<>();
        for (String status : fine) {
            Result result =
                    preflight(temp.resolve("f" + status), Map.of("FAKE_REGISTRY_STATUS", status));
            if (hasFailure(result, "레지스트리")) {
                wrong.add("정상인데 실패: " + status);
            }
        }
        for (String status : bad) {
            Result result =
                    preflight(temp.resolve("b" + status), Map.of("FAKE_REGISTRY_STATUS", status));
            if (!hasFailure(result, "레지스트리")) {
                wrong.add("이상한데 통과: " + status);
            }
        }
        Result unreachable =
                preflight(temp.resolve("unreachable"), Map.of("FAKE_REGISTRY_UNREACHABLE", "1"));

        // then
        assertThat(wrong).isEmpty();
        assertThat(hasFailure(unreachable, "레지스트리")).isTrue();
    }

    @Test
    void 태그를_주면_앱과_백업_이미지가_있는지_확인하고_없으면_실패한다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result found = preflight(temp.resolve("found"), Map.of(), "2222222");
        Result missing =
                preflight(
                        temp.resolve("missing"),
                        Map.of("FAKE_MANIFEST_FAIL", "2222222"),
                        "2222222");
        Result noTag = preflight(temp.resolve("notag"), Map.of());

        // then
        assertThat(found.calls())
                .contains(
                        "docker manifest inspect " + REGISTRY + "/gyeongjung-server:2222222",
                        "docker manifest inspect " + REGISTRY + "/gyeongjung-backup:2222222");
        assertThat(hasFailure(missing, "이미지 태그")).isTrue();
        assertThat(noTag.output()).contains("SKIP 이미지 태그");
    }

    @Test
    void 이웃_서비스가_정상이_아니면_실패하고_지정하지_않으면_건너뛴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result healthy = preflight(temp.resolve("ok"), Map.of("NEIGHBOR_HEALTH_URL", NEIGHBOR));
        Result sick =
                preflight(
                        temp.resolve("sick"),
                        Map.of("NEIGHBOR_HEALTH_URL", NEIGHBOR, "FAKE_NEIGHBOR_STATUS", "500"));
        Result none = preflight(temp.resolve("none"), Map.of());

        // then
        assertThat(hasFailure(healthy, "이웃 서비스")).isFalse();
        assertThat(hasFailure(sick, "이웃 서비스")).isTrue();
        assertThat(none.output()).contains("SKIP 이웃 서비스");
    }

    @Test
    void 하나가_실패해도_나머지를_끝까지_점검해서_모두_보여준다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // given
        Map<String, String> scenario =
                Map.of(
                        "FAKE_COMPOSE_VERSION", "2.9.0",
                        "FAKE_DF_AVAIL_KB", "1000",
                        "FAKE_CONFIG_FAIL", "1");

        // when
        Result result = preflight(temp, scenario, "2222222");

        // then
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.failures()).hasSize(3);
        assertThat(result.output()).contains("사전 점검 실패 (3개)");
    }

    @Test
    void 태그_형식이_올바르지_않으면_아무것도_점검하지_않고_2로_끝난다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        Result latest = preflight(temp.resolve("latest"), Map.of(), "latest");
        Result shortTag = preflight(temp.resolve("short"), Map.of(), "abc");

        // then
        assertThat(latest.exitCode()).isEqualTo(2);
        assertThat(shortTag.exitCode()).isEqualTo(2);
        assertThat(latest.calls()).isEmpty();
    }

    private static Map<String, Path> brokenEnvFiles(Path temp) throws IOException {
        Map<String, Path> broken = new LinkedHashMap<>();
        broken.put("파일 없음", temp.resolve("none.env"));
        broken.put("비밀번호가 비어 있음", brokenEnv(temp, "a", "DB_PASSWORD=secret", "DB_PASSWORD="));
        broken.put("필수 키 없음", brokenEnv(temp, "b", "GRAFANA_ADMIN_PASSWORD=secret\n", ""));
        broken.put("prod 오버레이 없음", brokenEnv(temp, "c", ":docker-compose.prod.yml", ""));
        broken.put(
                "local 오버레이 포함",
                brokenEnv(temp, "d", "prod.yml", "prod.yml:docker-compose.local.yml"));
        broken.put("권한 644", env(temp.resolve("e"), VALID_ENV, "rw-r--r--"));
        return broken;
    }

    private static Path brokenEnv(Path temp, String dir, String from, String to)
            throws IOException {
        return env(temp.resolve(dir), VALID_ENV.replace(from, to), "rw-------");
    }

    private static boolean hasFailure(Result result, String label) {
        return result.failures().stream().anyMatch(line -> line.startsWith("FAIL " + label));
    }

    private record Result(int exitCode, String output, List<String> calls) {
        List<String> failures() {
            return output.lines().filter(line -> line.startsWith("FAIL ")).toList();
        }
    }

    private static Result preflight(Path temp, Map<String, String> scenario, String... args)
            throws IOException, InterruptedException {
        Path state = prepareFakes(temp);

        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(RepositoryFiles.root().resolve("ops/preflight.sh").toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        configure(builder.environment(), temp, scenario);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("preflight.sh가 60초 안에 끝나야 한다").isTrue();
        return new Result(
                process.exitValue(), output, Files.readAllLines(state.resolve("calls.log")));
    }

    private static Path prepareFakes(Path temp) throws IOException {
        Path bin = Files.createDirectories(temp.resolve("bin"));
        Path state = Files.createDirectories(temp.resolve("state"));
        Files.createFile(state.resolve("calls.log"));
        install(bin.resolve("docker"), FAKE_DOCKER);
        install(bin.resolve("curl"), FAKE_CURL);
        install(bin.resolve("df"), FAKE_DF);
        return state;
    }

    private static void configure(Map<String, String> env, Path temp, Map<String, String> scenario)
            throws IOException {
        // 개발자의 셸에 설정된 값이 시험 결과를 바꾸지 않게 지운다.
        env.keySet()
                .removeIf(name ->
                        name.startsWith("COMPOSE_")
                                || name.startsWith("FAKE_")
                                || name.startsWith("PREFLIGHT_")
                                || name.startsWith("MIN_FREE_")
                                || name.startsWith("NEIGHBOR_")
                                || "IMAGE_REGISTRY".equals(name)
                                || "ENV_FILE".equals(name)
                                || "MEMINFO_FILE".equals(name));
        env.put("PATH", temp.resolve("bin") + File.pathSeparator + System.getenv("PATH"));
        env.put("FAKE_STATE", temp.resolve("state").toString());
        env.put("IMAGE_REGISTRY", REGISTRY);
        env.put("PREFLIGHT_PORTS", String.valueOf(freePort()));
        env.put("ENV_FILE", env(temp.resolve("default"), VALID_ENV, "rw-------").toString());
        env.put("MEMINFO_FILE", meminfo(temp.resolve("mem"), 8_000_000));
        env.putAll(scenario);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path env(Path dir, String content, String permissions) throws IOException {
        Path file = Files.writeString(Files.createDirectories(dir).resolve(".env"), content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }

    private static String meminfo(Path dir, long availableKb) throws IOException {
        Path file = Files.createDirectories(dir).resolve("meminfo");
        Files.writeString(
                file, "MemTotal:       24000000 kB\nMemAvailable:   " + availableKb + " kB\n");
        return file.toString();
    }

    private static void install(Path file, String script) throws IOException {
        Files.writeString(file, script);
        assertThat(file.toFile().setExecutable(true))
                .as("가짜 %s를 실행 가능하게 만든다", file.getFileName())
                .isTrue();
    }
}
