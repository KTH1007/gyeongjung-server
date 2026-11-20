package com.gyeongjung.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class RequestLogFilterRegistrationTest {

    @Test
    void 서블릿_웹_환경에서는_필터가_등록된다() {
        // given
        WebApplicationContextRunner runner =
                new WebApplicationContextRunner().withUserConfiguration(RequestLogFilter.class);

        // when & then
        runner.run(context -> assertThat(context).hasSingleBean(RequestLogFilter.class));
    }

    @Test
    void 웹_환경이_아니면_필터가_등록되지_않는다() {
        // given
        ApplicationContextRunner runner =
                new ApplicationContextRunner().withUserConfiguration(RequestLogFilter.class);

        // when & then
        runner.run(context -> assertThat(context).doesNotHaveBean(RequestLogFilter.class));
    }
}
