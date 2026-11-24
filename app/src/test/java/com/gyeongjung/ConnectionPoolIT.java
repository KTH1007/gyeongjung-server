package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 풀 크기와 대기 시간은 코드가 아니라 application.yml에 둔다. Spring Boot의 환경변수 규칙으로
// (SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE 등) 운영에서 덮어쓸 수 있다.
class ConnectionPoolIT extends AbstractMySqlIT {

    @Autowired DataSource dataSource;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void 기본_풀의_최대_크기는_5개다() throws SQLException {
        // given
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        // when
        int max = hikari.getMaximumPoolSize();

        // then
        assertThat(max).isEqualTo(5);
    }

    @Test
    void 풀이_가득_차면_3초만_기다리고_실패한다() throws SQLException {
        // given
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        // when
        long timeoutMillis = hikari.getConnectionTimeout();

        // then
        assertThat(timeoutMillis).isEqualTo(3_000);
    }

    @Test
    void 최소_유휴_연결은_최대와_같아서_고정_크기를_유지한다() throws SQLException {
        // given
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        // when
        int minimumIdle = hikari.getMinimumIdle();

        // then
        assertThat(minimumIdle).isEqualTo(hikari.getMaximumPoolSize());
    }

    @Test
    void hikaricp_connections_max_지표가_설정값과_같다() {
        // given
        String metric = "hikaricp.connections.max";

        // when
        double value = meterRegistry.get(metric).gauge().value();

        // then
        assertThat(value).isEqualTo(5.0);
    }
}
