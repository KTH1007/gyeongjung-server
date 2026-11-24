package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

// api가 생기면 적용될 Tomcat 과부하 한도다. worker는 웹 포트를 닫아서 지금은 효과가 없고, 값은 계획 2에서 측정해 확정한다.
// 환경변수(SERVER_TOMCAT_THREADS_MAX 등)로 덮어쓸 수 있다.
class TomcatOverloadSettingsIT extends AbstractMySqlIT {

    private static final Pattern NGINX_TOTAL_DEFAULT =
            Pattern.compile("LIMIT_CONN_TOTAL: \\$\\{LIMIT_CONN_TOTAL:-(\\d+)}");

    @Autowired Environment environment;

    @Test
    void 요청_처리_스레드는_최대_50개다() {
        // given
        String property = "server.tomcat.threads.max";

        // when
        Integer max = environment.getProperty(property, Integer.class);

        // then
        assertThat(max).isEqualTo(50);
    }

    @Test
    void 스레드가_모두_찼을_때_줄_세우는_연결은_50개다() {
        // given
        String property = "server.tomcat.accept-count";

        // when
        Integer acceptCount = environment.getProperty(property, Integer.class);

        // then
        assertThat(acceptCount).isEqualTo(50);
    }

    @Test
    void 동시에_받는_연결은_400개다() {
        // given
        String property = "server.tomcat.max-connections";

        // when
        Integer maxConnections = environment.getProperty(property, Integer.class);

        // then
        assertThat(maxConnections).isEqualTo(400);
    }

    // api가 하나만 남아도 nginx가 허용하는 전체 동시 연결을 혼자 받아야, 한도를 넘는 요청을
    // Tomcat이 연결 거절로 끊는 대신 nginx가 먼저 공통 오류 형식(503)으로 거절한다.
    @Test
    void Tomcat이_받는_연결은_nginx_전체_동시_연결_기본값_이상이다() throws IOException {
        // given
        String compose = Files.readString(RepositoryRoot.path().resolve("docker-compose.yml"));
        Matcher matcher = NGINX_TOTAL_DEFAULT.matcher(compose);
        assertThat(matcher.find()).as("compose에서 LIMIT_CONN_TOTAL 기본값").isTrue();
        int nginxTotal = Integer.parseInt(matcher.group(1));

        // when
        Integer tomcatMax = environment.getProperty("server.tomcat.max-connections", Integer.class);

        // then
        assertThat(tomcatMax).isGreaterThanOrEqualTo(nginxTotal);
    }
}
