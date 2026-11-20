package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

class GracefulShutdownIT extends AbstractMySqlIT {

    @Autowired Environment environment;

    @Test
    void 정상_종료_방식은_graceful이다() {
        // given
        String property = "server.shutdown";

        // when
        String shutdown = environment.getProperty(property);

        // then
        assertThat(shutdown).isEqualTo("graceful");
    }

    @Test
    void 종료_대기_시간은_30초이고_compose의_종료_유예_시간보다_짧다() {
        // given
        String property = "spring.lifecycle.timeout-per-shutdown-phase";
        Duration composeStopGracePeriod = Duration.ofSeconds(40);

        // when
        Duration timeout = environment.getProperty(property, Duration.class);

        // then
        assertThat(timeout).isEqualTo(Duration.ofSeconds(30)).isLessThan(composeStopGracePeriod);
    }
}
