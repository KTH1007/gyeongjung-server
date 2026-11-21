package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "logging.file.name=build/test-logs/gyeongjung.log",
            "management.server.port=0",
            "spring.profiles.active=probe"
        })
class ActuatorIT extends AbstractMySqlIT {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;

    @Test
    void management_포트의_health는_UP이다() throws Exception {
        // given
        String url = "http://127.0.0.1:" + managementPort + "/actuator/health";

        // when
        HttpResponse<String> response = get(url);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void management_포트의_prometheus에_JVM_메트릭과_공통_태그가_있다() throws Exception {
        // given
        String url = "http://127.0.0.1:" + managementPort + "/actuator/prometheus";

        // when
        HttpResponse<String> response = get(url);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("jvm_memory_used_bytes")
                .contains("application=\"gyeongjung-server\"")
                .contains("role=\"worker\"");
    }

    @Test
    void management_포트의_info에_활성_프로필이_나온다() throws Exception {
        // given
        String url = "http://127.0.0.1:" + managementPort + "/actuator/info";

        // when
        HttpResponse<String> response = get(url);

        // then
        assertThat(response.body()).contains("\"profiles\":[\"probe\"]");
    }

    @Test
    void 메인_포트에서는_actuator가_열리지_않는다() throws Exception {
        // given
        String prometheus = "http://127.0.0.1:" + port + "/actuator/prometheus";
        String health = "http://127.0.0.1:" + port + "/actuator/health";

        // when
        HttpResponse<String> prometheusResponse = get(prometheus);
        HttpResponse<String> healthResponse = get(health);

        // then
        assertThat(prometheusResponse.statusCode()).isEqualTo(404);
        assertThat(healthResponse.statusCode()).isEqualTo(404);
    }

    @Test
    void 웹_환경에서는_공통_예외_처리기와_요청_필터가_동작한다() throws Exception {
        // given
        String url = "http://127.0.0.1:" + port + "/no-such-path";

        // when
        HttpResponse<String> response = get(url);

        // then
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
        assertThat(response.headers().firstValue("X-Request-ID").orElse(""))
                .matches("[0-9a-f]{12}");
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
