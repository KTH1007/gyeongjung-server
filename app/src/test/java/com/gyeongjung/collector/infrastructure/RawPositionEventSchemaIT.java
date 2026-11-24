package com.gyeongjung.collector.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gyeongjung.shared.MySqlContainerSupport;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "logging.file.name=build/test-logs/gyeongjung.log")
@Transactional
class RawPositionEventSchemaIT extends MySqlContainerSupport {

    private static final DateTimeFormatter DB_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

    @Autowired RawPositionEventTestRepository events;
    @Autowired CollectionGapTestRepository gaps;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 같은_키를_두_번_저장하면_DataIntegrityViolationException이_발생한다() {
        // given
        Instant receivedAt = Instant.parse("2026-11-10T13:14:29Z");
        events.saveAndFlush(event("1234", receivedAt));

        // when & then
        assertThatThrownBy(() -> events.saveAndFlush(event("1234", receivedAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 수신_시각은_UTC로_저장된다() {
        // given
        events.saveAndFlush(event("1234", Instant.parse("2026-11-10T13:14:29Z")));

        // when
        String raw =
                jdbcTemplate.queryForObject(
                        "select cast(received_at as char) from raw_position_event where train_no = ?",
                        String.class,
                        "1234");

        // then
        assertThat(raw).isEqualTo("2026-11-10 13:14:29.000");
    }

    @Test
    void 갭을_저장하면_생성_시각과_수정_시각이_채워진다() {
        // given
        Instant before = Instant.now();
        CollectionGapEntity gap =
                new CollectionGapEntity(Instant.parse("2026-11-10T13:00:00Z"), "api_failure");

        // when
        gaps.saveAndFlush(gap);

        // then
        Instant after = Instant.now();
        assertThat(gap.getCreatedAt()).isBetween(before, after);
        assertThat(gap.getUpdatedAt()).isBetween(before, after);
    }

    @Test
    void 감사_시각은_DB에_저장된_값과_메모리_값이_같다() {
        // given
        CollectionGapEntity gap =
                new CollectionGapEntity(Instant.parse("2026-11-10T13:00:00Z"), "api_failure");

        // when
        gaps.saveAndFlush(gap);
        String raw =
                jdbcTemplate.queryForObject(
                        "select cast(created_at as char) from collection_gap where id = ?",
                        String.class,
                        gap.getId());

        // then
        Instant stored = LocalDateTime.parse(raw, DB_FORMAT).toInstant(ZoneOffset.UTC);
        assertThat(gap.getCreatedAt()).isEqualTo(stored);
    }

    private static RawPositionEventEntity event(String trainNo, Instant receivedAt) {
        return RawPositionEventEntity.builder()
                .trainNo(trainNo)
                .stationId("K337")
                .stationName("임진강")
                .trainStatus(1)
                .direction(0)
                .destinationId("K331")
                .destinationName("문산")
                .express(false)
                .lastCar(false)
                .receivedAt(receivedAt)
                .polledAt(receivedAt.plusSeconds(5))
                .build();
    }
}
