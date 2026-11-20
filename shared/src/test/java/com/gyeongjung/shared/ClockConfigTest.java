package com.gyeongjung.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    void Clock_빈은_UTC_시계다() {
        // given
        ClockConfig config = new ClockConfig();

        // when
        Clock clock = config.clock();

        // then
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void Clock_빈은_밀리초_단위로_자른_시각을_돌려준다() {
        // given
        Clock clock = new ClockConfig().clock();

        // when
        List<Instant> samples = IntStream.range(0, 20).mapToObj(i -> clock.instant()).toList();

        // then
        assertThat(samples).allMatch(instant -> instant.getNano() % 1_000_000 == 0);
    }
}
