package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class MdcLogIT extends AbstractMySqlIT {

    private static final Logger LOG = LoggerFactory.getLogger(MdcLogIT.class);

    @Test
    void MDC에_넣은_pollId가_로그_줄에_출력된다(CapturedOutput output) {
        // given
        MDC.put("pollId", "a3f9b2c1");

        // when
        try {
            LOG.info("폴링 시작 pollId 확인");
        } finally {
            MDC.remove("pollId");
        }

        // then
        assertThat(lineWith("폴링 시작 pollId 확인", output)).contains("pollId=a3f9b2c1");
    }

    @Test
    void MDC에_넣은_requestId가_로그_줄에_출력된다(CapturedOutput output) {
        // given
        MDC.put("requestId", "req-7");

        // when
        try {
            LOG.info("요청 처리 requestId 확인");
        } finally {
            MDC.remove("requestId");
        }

        // then
        assertThat(lineWith("요청 처리 requestId 확인", output)).contains("requestId=req-7");
    }

    @Test
    void MDC가_비어_있으면_로그_줄에_값이_없다(CapturedOutput output) {
        // given
        MDC.clear();

        // when
        LOG.info("MDC 없음 확인");

        // then
        assertThat(lineWith("MDC 없음 확인", output)).doesNotContain("pollId=", "requestId=");
    }

    private static String lineWith(String message, CapturedOutput output) {
        return output.getAll()
                .lines()
                .filter(line -> line.contains(message))
                .findFirst()
                .orElseThrow();
    }
}
