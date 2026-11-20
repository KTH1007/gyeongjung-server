package com.gyeongjung.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

class MaskingMessageConverterTest {

    @Test
    void 로그_메시지에_든_서울_키를_가린다() {
        // given
        Logger logger = new LoggerContext().getLogger("test");
        LoggingEvent event =
                new LoggingEvent(
                        "fqcn",
                        logger,
                        Level.WARN,
                        "호출 실패: {}",
                        null,
                        new Object[] {"http://swopenapi.seoul.go.kr/api/subway/KEY1/json/x"});
        MaskingMessageConverter converter = new MaskingMessageConverter();

        // when
        String converted = converter.convert(event);

        // then
        assertThat(converted)
                .isEqualTo("호출 실패: http://swopenapi.seoul.go.kr/api/subway/***/json/x");
    }
}
