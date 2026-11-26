package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 서버에서는 같은 호스트의 다른 서비스가 9090을 쓸 수 있어서 우리 Prometheus가 다른 포트에 열린다.
// 스모크의 기본 주소가 localhost:9090으로 고정돼 있으면 다른 서비스의 Prometheus를 조회해서 건강한 배포도 실패한다.
// docker와 curl을 가짜로 바꿔 끼워서 스모크가 어느 주소로 묻는지만 확인한다.
class SmokeScriptTest {

    // compose port prometheus 9090 질문에만 FAKE_PROMETHEUS_ADDRESS로 답하고, 나머지는 모두 실패한다.
    private static final String FAKE_DOCKER =
            """
            #!/bin/bash
            if [ "$*" = "compose port prometheus 9090" ] && [ -n "${FAKE_PROMETHEUS_ADDRESS:-}" ]; then
              echo "$FAKE_PROMETHEUS_ADDRESS"
              exit 0
            fi
            exit 1
            """;

    // 받은 인자를 기록하고 실패한다. 스모크는 실패해도 끝까지 돈다.
    private static final String FAKE_CURL =
            """
            #!/bin/bash
            printf '%s\\n' "$*" >> "$FAKE_CALLS"
            exit 22
            """;

    @Test
    void Prometheus_주소는_compose가_알려준_포트를_따른다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        String query =
                firstPrometheusQuery(temp, Map.of("FAKE_PROMETHEUS_ADDRESS", "127.0.0.1:9091"));

        // then
        assertThat(query).contains("http://127.0.0.1:9091/api/v1/query");
    }

    @Test
    void compose가_포트를_알려주지_못하면_localhost_9090을_쓴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        String query = firstPrometheusQuery(temp, Map.of());

        // then
        assertThat(query).contains("http://localhost:9090/api/v1/query");
    }

    @Test
    void PROMETHEUS_URL을_지정하면_compose에_묻지_않고_그_주소를_쓴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        String query =
                firstPrometheusQuery(
                        temp,
                        Map.of(
                                "FAKE_PROMETHEUS_ADDRESS", "127.0.0.1:9091",
                                "PROMETHEUS_URL", "http://prometheus.test:9999"));

        // then
        assertThat(query).contains("http://prometheus.test:9999/api/v1/query");
    }

    // docker compose port는 서비스가 떠 있어도 포트를 공개하지 않았으면 실패하지 않고 "invalid IP:0"을 출력한다.
    @Test
    void compose가_포트가_공개되지_않았다고_답하면_localhost_9090을_쓴다(@TempDir Path temp)
            throws IOException, InterruptedException {
        // when
        String query =
                firstPrometheusQuery(temp, Map.of("FAKE_PROMETHEUS_ADDRESS", "invalid IP:0"));

        // then
        assertThat(query).contains("http://localhost:9090/api/v1/query");
    }

    private static String firstPrometheusQuery(Path temp, Map<String, String> env)
            throws IOException, InterruptedException {
        Path calls = Files.createFile(temp.resolve("calls.txt"));
        installFake(temp.resolve("docker"), FAKE_DOCKER);
        installFake(temp.resolve("curl"), FAKE_CURL);
        Path smoke = RepositoryRoot.path().toAbsolutePath().normalize().resolve("ops/smoke.sh");
        ProcessBuilder builder = new ProcessBuilder("bash", smoke.toString());
        // 개발자의 셸에 설정된 값이 시험 결과를 바꾸지 않게 지운다.
        builder.environment()
                .keySet()
                .removeIf(name -> "PROMETHEUS_URL".equals(name) || name.startsWith("EXPECTED_"));
        builder.environment().put("PATH", temp + File.pathSeparator + System.getenv("PATH"));
        builder.environment().put("FAKE_CALLS", calls.toString());
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("smoke.sh가 60초 안에 끝나야 한다").isTrue();
        return Files.readAllLines(calls).stream()
                .filter(line -> line.contains("/api/v1/query"))
                .findFirst()
                .orElse("(Prometheus 조회 없음)");
    }

    private static void installFake(Path file, String script) throws IOException {
        Files.writeString(file, script);
        assertThat(file.toFile().setExecutable(true))
                .as("가짜 %s를 실행 가능하게 만든다", file.getFileName())
                .isTrue();
    }
}
