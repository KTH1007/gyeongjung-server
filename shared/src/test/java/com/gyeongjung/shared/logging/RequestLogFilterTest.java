package com.gyeongjung.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLogFilterTest {

    private final RequestLogFilter filter = new RequestLogFilter();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void 요청_ID_헤더가_없으면_새로_만들어_응답_헤더로_돌려준다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (req, res) -> {});

        // then
        assertThat(response.getHeader("X-Request-ID")).matches("[0-9a-f]{12}");
    }

    @Test
    void 요청_ID_헤더가_있으면_그_값을_쓴다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.addHeader("X-Request-ID", "req-7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        // when
        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get("requestId")));

        // then
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("req-7");
        assertThat(inChain.get()).isEqualTo("req-7");
    }

    @Test
    void 요청_ID_헤더에_허용하지_않는_문자가_있으면_새로_만든다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.addHeader("X-Request-ID", "req 7 INJECT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (req, res) -> {});

        // then
        assertThat(response.getHeader("X-Request-ID")).matches("[0-9a-f]{12}");
    }

    @Test
    void 요청_ID_헤더가_너무_길면_새로_만든다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.addHeader("X-Request-ID", "a".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (req, res) -> {});

        // then
        assertThat(response.getHeader("X-Request-ID")).matches("[0-9a-f]{12}");
    }

    @Test
    void 체인_안에서는_MDC에_requestId가_있고_끝나면_지워진다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        // when
        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get("requestId")));

        // then
        assertThat(inChain.get()).isNotNull();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void 접근_로그에_메서드_경로_상태_처리시간이_들어간다() throws Exception {
        // given
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/subscriptions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(201);

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(lastLog()).matches("POST /api/v1/subscriptions 201 \\d+ms device=-");
    }

    @Test
    void 접근_로그에_쿼리_스트링은_남기지_않는다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.setQueryString("serviceKey=abc&x=1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (req, res) -> {});

        // then
        assertThat(lastLog()).doesNotContain("serviceKey", "abc", "x=1");
    }

    @Test
    void device_헤더가_있으면_접근_로그에_들어간다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.addHeader("X-Device-Id", "9c1e5a77-0b1f-4f55-9a7e-3d2f1c0b8a44");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (req, res) -> {});

        // then
        assertThat(lastLog()).endsWith("device=9c1e5a77-0b1f-4f55-9a7e-3d2f1c0b8a44");
    }

    @Test
    void device_헤더에_허용하지_않는_문자가_있으면_하이픈으로_남긴다() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.addHeader("X-Device-Id", "dev ice\tINJECT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        filter.doFilter(request, response, (req, res) -> {});

        // then
        assertThat(lastLog()).endsWith("device=-");
    }

    @Test
    void 체인에서_예외가_나도_MDC가_지워지고_접근_로그는_남는다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing =
                (req, res) -> {
                    throw new ServletException("boom");
                };

        // when & then
        assertThatThrownBy(() -> filter.doFilter(request, response, failing))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(appender.list).hasSize(1);
    }

    private String lastLog() {
        return appender.list.getLast().getFormattedMessage();
    }
}
