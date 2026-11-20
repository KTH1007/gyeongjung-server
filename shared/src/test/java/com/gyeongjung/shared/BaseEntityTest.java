package com.gyeongjung.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AuditingTestConfig.class)
class BaseEntityTest {

    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired SampleRepository repository;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 저장하면_PK가_AUTO_INCREMENT로_생성된다() {
        // given
        SampleEntity first = new SampleEntity("a");
        SampleEntity second = new SampleEntity("b");

        // when
        repository.saveAndFlush(first);
        repository.saveAndFlush(second);

        // then
        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isGreaterThan(first.getId());
    }

    @Test
    void 저장하면_생성_시각과_수정_시각이_Clock_시각으로_채워진다() {
        // given
        Instant now = Instant.parse("2026-11-10T13:14:29Z");
        clock.set(now);

        // when
        SampleEntity saved = repository.saveAndFlush(new SampleEntity("a"));

        // then
        assertThat(saved.getCreatedAt()).isEqualTo(now);
        assertThat(saved.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void 수정하면_수정_시각만_바뀌고_생성_시각은_그대로다() {
        // given
        Instant created = Instant.parse("2026-11-10T13:14:29Z");
        Instant modified = Instant.parse("2026-11-10T13:20:00Z");
        clock.set(created);
        SampleEntity saved = repository.saveAndFlush(new SampleEntity("a"));

        // when
        clock.set(modified);
        saved.rename("b");
        repository.saveAndFlush(saved);

        // then
        assertThat(saved.getCreatedAt()).isEqualTo(created);
        assertThat(saved.getUpdatedAt()).isEqualTo(modified);
    }

    @Test
    void 시각은_UTC로_DB에_저장된다() {
        // given
        clock.set(Instant.parse("2026-11-10T13:14:29Z"));

        // when
        SampleEntity saved = repository.saveAndFlush(new SampleEntity("a"));

        // then
        String raw =
                jdbcTemplate.queryForObject(
                        "select cast(created_at as char) from sample_entity where id = ?",
                        String.class,
                        saved.getId());
        assertThat(raw).startsWith("2026-11-10 13:14:29");
    }
}
