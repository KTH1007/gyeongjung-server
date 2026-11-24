package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 풀 크기만 1로 줄인다. 대기 시간은 덮어쓰지 않아서 application.yml의 값을 그대로 시험한다.
// 로그 파일 속성은 AbstractMySqlIT와 같아야 하는 값은 아니지만 시험 로그를 build 폴더에 모으려고 같이 둔다.
@SpringBootTest(
        properties = {
            "logging.file.name=build/test-logs/gyeongjung.log",
            "spring.datasource.hikari.maximum-pool-size=1"
        })
class ConnectionPoolExhaustionIT extends AbstractMySqlIT {

    @Autowired DataSource dataSource;

    @Test
    void 풀이_가득_차면_연결_대기_시간_안에_빠르게_실패한다() throws SQLException {
        // given
        try (Connection held = dataSource.getConnection()) {
            long start = System.nanoTime();

            // when
            Throwable thrown = catchThrowable(dataSource::getConnection);
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            // then
            assertThat(held.isValid(1)).isTrue();
            assertThat(thrown).isInstanceOf(SQLTransientConnectionException.class);
            assertThat(waited).isBetween(Duration.ofMillis(2_500), Duration.ofSeconds(6));
        }
    }
}
