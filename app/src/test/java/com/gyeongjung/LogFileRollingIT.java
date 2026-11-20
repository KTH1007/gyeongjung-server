package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

class LogFileRollingIT extends AbstractMySqlIT {

    private static final Logger LOG = LoggerFactory.getLogger(LogFileRollingIT.class);

    @Test
    void 로그를_남기면_지정한_위치의_파일에_저장되고_비밀_값은_가려진다() throws IOException {
        // given
        Path logFile = Path.of("build/test-logs/gyeongjung.log");
        String url = "http://swopenapi.seoul.go.kr/api/subway/FILEKEY123/json/realtimePosition";

        // when
        LOG.warn("서울 API 호출 실패: {}", url);

        // then
        assertThat(Files.readString(logFile))
                .doesNotContain("FILEKEY123")
                .contains("api/subway/***/json/realtimePosition");
    }

    @Test
    void 롤링_정책은_날짜별로_나누고_14일_50MB_1GB로_정리한다() {
        // given
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Appender<ILoggingEvent> appender =
                context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE");

        // when
        SizeAndTimeBasedRollingPolicy<?> policy =
                (SizeAndTimeBasedRollingPolicy<?>)
                        ((RollingFileAppender<?>) appender).getRollingPolicy();

        // then
        assertThat(policy.getMaxHistory()).isEqualTo(14);
        assertThat(policy.isCleanHistoryOnStart()).isTrue();
        assertThat(policy.getFileNamePattern()).endsWith(".%d{yyyy-MM-dd, UTC}.%i.gz");
        assertThat(System.getProperty("LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE"))
                .isEqualTo(String.valueOf(DataSize.ofMegabytes(50).toBytes()));
        assertThat(System.getProperty("LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP"))
                .isEqualTo(String.valueOf(DataSize.ofGigabytes(1).toBytes()));
    }
}
