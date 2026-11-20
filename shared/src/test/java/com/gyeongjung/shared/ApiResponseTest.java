package com.gyeongjung.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void of는_데이터를_그대로_담는다() {
        // given
        String data = "hello";

        // when
        ApiResponse<String> response = ApiResponse.of(data);

        // then
        assertThat(response.data()).isEqualTo("hello");
    }
}
