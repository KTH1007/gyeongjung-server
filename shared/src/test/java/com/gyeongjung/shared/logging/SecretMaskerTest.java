package com.gyeongjung.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretMaskerTest {

    @Test
    void 서울_URL_경로의_키를_가린다() {
        // given
        String text =
                "http://swopenapi.seoul.go.kr/api/subway/ABCDEF/json/realtimePosition/0/300/경의중앙선";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked)
                .isEqualTo(
                        "http://swopenapi.seoul.go.kr/api/subway/***/json/realtimePosition/0/300/경의중앙선");
    }

    @Test
    void serviceKey_쿼리_값을_가린다() {
        // given
        String text = "https://example.go.kr/api?serviceKey=abc%2Bdef&format=json";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked).isEqualTo("https://example.go.kr/api?serviceKey=***&format=json");
    }

    @Test
    void serviceKey가_쿼리의_마지막이어도_가린다() {
        // given
        String text = "https://example.go.kr/api?format=json&serviceKey=abc";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked).isEqualTo("https://example.go.kr/api?format=json&serviceKey=***");
    }

    @Test
    void 디스코드_웹훅의_id와_토큰을_가린다() {
        // given
        String text = "https://discord.com/api/webhooks/123/tok";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked).isEqualTo("https://discord.com/api/webhooks/***");
    }

    @Test
    void 한_문장에_여러_비밀이_있어도_모두_가린다() {
        // given
        String text =
                "GET http://swopenapi.seoul.go.kr/api/subway/KEY1/json/x failed, "
                        + "alert to https://discord.com/api/webhooks/123/tok";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked)
                .isEqualTo(
                        "GET http://swopenapi.seoul.go.kr/api/subway/***/json/x failed, "
                                + "alert to https://discord.com/api/webhooks/***");
    }

    @Test
    void 따옴표로_둘러싸인_URL의_키도_가린다() {
        // given
        String text =
                "I/O error on GET \"http://swopenapi.seoul.go.kr/api/subway/KEY1/json/x\" failed";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked)
                .isEqualTo(
                        "I/O error on GET \"http://swopenapi.seoul.go.kr/api/subway/***/json/x\" failed");
    }

    @Test
    void 서울_호스트의_대소문자가_달라도_키를_가린다() {
        // given
        String text = "http://swopenAPI.seoul.go.kr/api/subway/KEY1/json/x";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked).isEqualTo("http://swopenAPI.seoul.go.kr/api/subway/***/json/x");
    }

    @Test
    void 디스코드_호스트의_대소문자가_달라도_웹훅을_가린다() {
        // given
        String text = "https://DISCORD.COM/api/webhooks/123/tok";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked).isEqualTo("https://DISCORD.COM/api/webhooks/***");
    }

    @Test
    void 버전_경로가_있는_디스코드_웹훅도_가린다() {
        // given
        String text = "https://discord.com/api/v10/webhooks/123/tok";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked).isEqualTo("https://discord.com/api/v10/webhooks/***");
    }

    @Test
    void 비밀이_없는_문자열은_그대로_둔다() {
        // given
        String text = "폴링 성공: 이벤트 26건 저장";

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked).isEqualTo(text);
    }

    @Test
    void null은_null을_돌려준다() {
        // given
        String text = null;

        // when
        String masked = SecretMasker.mask(text);

        // then
        assertThat(masked).isNull();
    }
}
