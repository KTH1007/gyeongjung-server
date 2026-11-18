package com.gyeongjung.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
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
}
