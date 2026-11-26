package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
            // 셸에 설정된 COMPOSE_FILE 같은 값이 시험 결과를 바꾸지 않게 지운다.
            builder.environment().keySet().removeIf(name -> name.startsWith("COMPOSE_"));
            builder.redirectError(errors.toFile());
            Process process = builder.start();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            assertThat(finished && process.exitValue() == 0)
                    .as("docker compose config가 실패했다: %s", Files.readString(errors))
                    .isTrue();
            return output;
        } finally {
            Files.deleteIfExists(errors);
        }
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
}
