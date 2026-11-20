package com.gyeongjung.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.Test;

class MaskingThrowableConverterTest {

    @Test
    void 예외_메시지에_든_서울_키를_가린다() {
        // given
        Logger logger = new LoggerContext().getLogger("test");
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.ERROR, "수집 실패", null, null);
        event.setThrowableProxy(
                new ThrowableProxy(
                        new IllegalStateException(
                                "GET http://swopenapi.seoul.go.kr/api/subway/KEY1/json/x failed")));
        MaskingThrowableConverter converter = new MaskingThrowableConverter();
        converter.start();

        // when
        String converted = converter.convert(event);

        // then
        assertThat(converted)
                .doesNotContain("KEY1")
                .contains("GET http://swopenapi.seoul.go.kr/api/subway/***/json/x failed");
    }
}
