package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SecretMaskingLogIT extends AbstractMySqlIT {

    private static final Logger LOG = LoggerFactory.getLogger(SecretMaskingLogIT.class);
    private static final String URL =
            "http://swopenapi.seoul.go.kr/api/subway/SECRETKEY123/json/realtimePosition/0/300/경의중앙선";

    @Test
    void 메시지에_든_서울_키는_출력에_남지_않는다(CapturedOutput output) {
        // given
        String url = URL;

        // when
        LOG.warn("서울 API 호출 실패: {}", url);

        // then
        assertThat(output.getAll())
                .doesNotContain("SECRETKEY123")
                .contains("api/subway/***/json/realtimePosition");
    }

    @Test
    void 예외_메시지와_스택트레이스에_든_서울_키는_출력에_남지_않는다(CapturedOutput output) {
        // given
        IllegalStateException cause = new IllegalStateException("I/O error on GET \"" + URL + "\"");

        // when
        LOG.error("수집 실패", cause);

        // then
        assertThat(output.getAll())
                .doesNotContain("SECRETKEY123")
                .contains("api/subway/***/json/realtimePosition");
    }
}
