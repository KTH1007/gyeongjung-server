package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

// 서버(prod) compose가 다른 서비스와 같이 쓰는 호스트에서 우리 몫의 포트만 쓰는지 확인한다.
// 파일을 글자로 읽으면 !reset과 !override 같은 합치기 규칙이 반영되지 않아서 docker compose가 합친 최종 설정을 읽는다.
// !reset을 읽으려면 compose 2.24 이상이 필요하다.
class ComposeProdOverlayTest {

    // .env의 값이 섞이면 같은 시험이 사람마다 다르게 나오므로 환경 파일을 직접 만들어 넘긴다.
    private static final String REQUIRED_ENV =
            """
            DB_USERNAME=test
            DB_PASSWORD=test
            GRAFANA_ADMIN_PASSWORD=test
            """;

    // 서버 메모리를 다른 서비스와 나눠 쓰므로 우리 몫의 상한을 둔다. 올릴 때는 서버의 가용 메모리를 확인하고 올린다.
    private static final long MEMORY_BUDGET_BYTES = 6L * 1024 * 1024 * 1024;
    // 같은 서버의 다른 서비스는 기본 가중치(1024)를 쓰므로, 우리는 그보다 낮아야 CPU가 모자랄 때 양보한다.
    private static final int DEFAULT_CPU_SHARES = 1024;

    @Test
    void 서버_설정은_127_0_0_1이_아닌_곳에_포트를_열지_않는다() throws IOException, InterruptedException {
        // given
        Map<String, Object> config = effectiveProdConfig("");

        // when
        List<String> exposed = new ArrayList<>();
        services(config)
                .forEach((name, service) ->
                        ports(service).stream()
                                .filter(port -> !"127.0.0.1".equals(port.get("host_ip")))
                                .forEach(port -> exposed.add(name + " " + port)));

        // then
        assertThat(exposed).as("공개 포트는 모두 127.0.0.1에만 묶여야 한다 (외부 공개는 #39에서 한다)").isEmpty();
    }

    @Test
    void Prometheus_호스트_포트는_환경변수로_바꿀_수_있고_비우면_9090이다() throws IOException, InterruptedException {
        // given
        Map<String, Object> overridden = effectiveProdConfig("PROMETHEUS_HOST_PORT=9091\n");
        Map<String, Object> defaulted = effectiveProdConfig("");

        // when & then
        assertThat(publishedPort(overridden, "prometheus")).isEqualTo("9091");
        assertThat(publishedPort(defaulted, "prometheus")).isEqualTo("9090");
    }

    @Test
    void 배포할_이미지는_환경변수로_바꿀_수_있고_비우면_로컬_이미지를_쓴다() throws IOException, InterruptedException {
        // given
        String app = "ghcr.io/example/gyeongjung-server:abc1234";
        String backup = "ghcr.io/example/gyeongjung-backup:abc1234";
        Map<String, Object> deployed =
                effectiveProdConfig("APP_IMAGE=" + app + "\nBACKUP_IMAGE=" + backup + "\n");
        Map<String, Object> local = effectiveProdConfig("");

        // when & then
        assertThat(services(deployed).get("worker")).containsEntry("image", app);
        assertThat(services(deployed).get("backup")).containsEntry("image", backup);
        assertThat(services(local).get("worker")).containsEntry("image", "gyeongjung-server");
        assertThat(services(local).get("backup")).containsEntry("image", "gyeongjung-backup");
    }

    @Test
    void 서버에서_뜨는_모든_서비스에_메모리_한도가_있다() throws IOException, InterruptedException {
        // given
        Map<String, Map<String, Object>> services = serverServices();

        // when
        List<String> unlimited =
                services.entrySet().stream()
                        .filter(entry -> !entry.getValue().containsKey("mem_limit"))
                        .map(Map.Entry::getKey)
                        .toList();

        // then
        assertThat(services).as("서버에서 뜨는 서비스가 있어야 한다").isNotEmpty();
        assertThat(unlimited).as("mem_limit이 없는 서비스").isEmpty();
    }

    @Test
    void 메모리_한도의_합은_상한_이하이다() throws IOException, InterruptedException {
        // given
        Map<String, Map<String, Object>> services = serverServices();

        // when
        long total =
                services.values().stream()
                        .mapToLong(service -> bytes(service.get("mem_limit")))
                        .sum();

        // then
        assertThat(total)
                .as("서버에서 뜨는 서비스의 mem_limit 합(바이트)")
                .isLessThanOrEqualTo(MEMORY_BUDGET_BYTES);
    }

    @Test
    void 서버에서_뜨는_모든_서비스에_공통_보호_설정이_있다() throws IOException, InterruptedException {
        // given
        Map<String, Map<String, Object>> services = serverServices();

        // when
        List<String> missing = new ArrayList<>();
        services.forEach((name, service) -> {
            if (!"500".equals(String.valueOf(service.get("oom_score_adj")))) {
                missing.add(name + " oom_score_adj");
            }
            if (!securityOptions(service).contains("no-new-privileges:true")) {
                missing.add(name + " no-new-privileges");
            }
        });

        // then
        assertThat(missing).as("공통 보호 설정이 빠진 서비스").isEmpty();
    }

    @Test
    void 우리가_만든_이미지의_서비스는_리눅스_권한을_모두_버린다() throws IOException, InterruptedException {
        // given
        Map<String, Map<String, Object>> services = serverServices();

        // when
        List<String> ours =
                services.entrySet().stream()
                        .filter(entry ->
                                String.valueOf(entry.getValue().get("image"))
                                        .contains("gyeongjung-"))
                        .map(Map.Entry::getKey)
                        .toList();
        List<String> keeping =
                ours.stream()
                        .filter(name -> !List.of("ALL").equals(services.get(name).get("cap_drop")))
                        .toList();

        // then
        // 이미지 이름 규칙이 바뀌어 대상이 하나도 안 잡히면 아래 검사가 아무것도 확인하지 못하고 통과한다.
        assertThat(ours).contains("worker", "backup");
        assertThat(keeping).as("cap_drop: [ALL]이 없는 서비스").isEmpty();
    }

    @Test
    void CPU_가중치는_모든_서비스에_있고_다른_서비스의_기본값보다_낮다() throws IOException, InterruptedException {
        // given
        Map<String, Map<String, Object>> services = serverServices();

        // when
        List<String> invalid =
                services.entrySet().stream()
                        .filter(entry ->
                                cpuShares(entry.getValue()) <= 0
                                        || cpuShares(entry.getValue()) >= DEFAULT_CPU_SHARES)
                        .map(Map.Entry::getKey)
                        .toList();

        // then
        assertThat(invalid).as("cpu_shares가 없거나 기본값(1024) 이상인 서비스").isEmpty();
    }

    @Test
    void CPU_가중치는_핵심_경로가_배경_작업보다_높다() throws IOException, InterruptedException {
        // given
        Map<String, Map<String, Object>> services = serverServices();

        // when & then
        assertThat(cpuShares(services.get("worker")))
                .as("수집(worker)은 놓치면 복구할 수 없고, 지표 저장(prometheus)은 잠깐 늦어도 된다")
                .isGreaterThan(cpuShares(services.get("prometheus")));
    }

    private static Map<String, Object> effectiveProdConfig(String extraEnv)
            throws IOException, InterruptedException {
        Path envFile = Files.createTempFile("compose-env", ".env");
        try {
            Files.writeString(envFile, REQUIRED_ENV + extraEnv);
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(composeConfig(envFile));
        } finally {
            Files.deleteIfExists(envFile);
        }
    }

    private static String composeConfig(Path envFile) throws IOException, InterruptedException {
        Path errors = Files.createTempFile("compose-errors", ".txt");
        try {
            ProcessBuilder builder = new ProcessBuilder(composeCommand(envFile));
            // 셸에 설정된 값이 시험 결과를 바꾸지 않게 지운다. Compose는 --env-file보다 셸의 값을 먼저 쓴다.
            Set<String> controlled = valuesTheTestControls(envFile);
            builder.environment()
                    .keySet()
                    .removeIf(name -> name.startsWith("COMPOSE_") || controlled.contains(name));
            builder.redirectError(errors.toFile());
            ProcessOutput run = ProcessOutput.run(builder, 60);
            assertThat(run.finished() && run.exitCode() == 0)
                    .as("docker compose config가 실패했다: %s", Files.readString(errors))
                    .isTrue();
            return run.output();
        } finally {
            Files.deleteIfExists(errors);
        }
    }

    // 환경 파일에 적은 이름과, 파일에 없어도 기본값 시험이 비어 있어야 하는 이름들이다.
    private static Set<String> valuesTheTestControls(Path envFile) throws IOException {
        Set<String> names =
                new HashSet<>(Set.of("PROMETHEUS_HOST_PORT", "APP_IMAGE", "BACKUP_IMAGE"));
        for (String line : Files.readAllLines(envFile)) {
            if (line.contains("=")) {
                names.add(line.substring(0, line.indexOf('=')));
            }
        }
        return names;
    }

    private static List<String> composeCommand(Path envFile) {
        Path root = RepositoryRoot.path().toAbsolutePath().normalize();
        return List.of(
                "docker",
                "compose",
                "--env-file",
                envFile.toString(),
                "--project-directory",
                root.toString(),
                "-f",
                root.resolve("docker-compose.yml").toString(),
                "-f",
                root.resolve("docker-compose.prod.yml").toString(),
                "--profile",
                "*",
                "config");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services(Map<String, Object> config) {
        return (Map<String, Map<String, Object>>) config.get("services");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ports(Map<String, Object> service) {
        return (List<Map<String, Object>>) service.getOrDefault("ports", List.of());
    }

    private static String publishedPort(Map<String, Object> config, String service) {
        List<Map<String, Object>> ports = ports(services(config).get(service));
        assertThat(ports).as("%s 서비스에 공개 포트가 하나 있어야 한다", service).hasSize(1);
        return String.valueOf(ports.get(0).get("published"));
    }

    // 서버에서 실제로 뜨는 서비스만 본다. profiles가 있는 서비스는 도구용(복구 리허설, 마이그레이션 CLI)이라 서버에 배포하지 않는다.
    private static Map<String, Map<String, Object>> serverServices()
            throws IOException, InterruptedException {
        Map<String, Map<String, Object>> servers =
                new LinkedHashMap<>(services(effectiveProdConfig("")));
        servers.values().removeIf(service -> service.containsKey("profiles"));
        return servers;
    }

    @SuppressWarnings("unchecked")
    private static List<String> securityOptions(Map<String, Object> service) {
        return (List<String>) service.getOrDefault("security_opt", List.of());
    }

    private static long bytes(Object memLimit) {
        return memLimit == null ? 0 : Long.parseLong(String.valueOf(memLimit));
    }

    private static int cpuShares(Map<String, Object> service) {
        Object value = service.get("cpu_shares");
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }
}
