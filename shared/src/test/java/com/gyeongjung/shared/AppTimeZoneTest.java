package com.gyeongjung.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AppTimeZoneTest {

    @Test
    void KST_문자열을_UTC_시점으로_변환한다() {
        // given
        String kst = "2026-11-10 22:14:29";

        // when
        Instant result = AppTimeZone.parseKst(kst);

        // then
        assertThat(result).isEqualTo(Instant.parse("2026-11-10T13:14:29Z"));
    }

    @Test
    void KST_자정은_UTC_전날_15시다() {
        // given
        String kstMidnight = "2026-11-11 00:00:00";

        // when
        Instant result = AppTimeZone.parseKst(kstMidnight);

        // then
        assertThat(result).isEqualTo(Instant.parse("2026-11-10T15:00:00Z"));
    }

    @Test
    void 형식이_다르면_IllegalArgumentException을_던진다() {
        // given
        String otherFormat = "20261110";

        // when & then
        assertThatThrownBy(() -> AppTimeZone.parseKst(otherFormat))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 존재하지_않는_날짜는_IllegalArgumentException을_던진다() {
        // given
        String nonexistentDate = "2026-02-30 12:00:00";

        // when & then
        assertThatThrownBy(() -> AppTimeZone.parseKst(nonexistentDate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 평년의_2월_29일은_IllegalArgumentException을_던진다() {
        // given
        String notLeapYear = "2027-02-29 12:00:00";

        // when & then
        assertThatThrownBy(() -> AppTimeZone.parseKst(notLeapYear))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 윤년의_2월_29일은_변환한다() {
        // given
        String leapDay = "2028-02-29 12:00:00";

        // when
        Instant result = AppTimeZone.parseKst(leapDay);

        // then
        assertThat(result).isEqualTo(Instant.parse("2028-02-29T03:00:00Z"));
    }

    @Test
    void UTC_15시는_KST로_보면_다음날이다() {
        // given
        Instant utc = Instant.parse("2026-11-10T15:00:00Z");

        // when
        LocalDate kstDate = AppTimeZone.inKst(utc).toLocalDate();

        // then
        assertThat(kstDate).isEqualTo(LocalDate.of(2026, 11, 11));
    }
}
