package com.gyeongjung.shared;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new ProbeController())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

    @Test
    void 업무_예외는_해당_상태와_code가_담긴_problem_json으로_응답한다() throws Exception {
        // given
        String path = "/probe/business";

        // when
        ResultActions result = mockMvc.perform(get(path));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("subscription 7 not found"));
    }

    @Test
    void 예상하지_못한_예외는_내부_메시지를_숨기고_500으로_응답한다() throws Exception {
        // given
        String path = "/probe/unexpected";

        // when
        ResultActions result = mockMvc.perform(get(path));

        // then
        result.andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(not(containsString("secret-detail"))));
    }

    @Test
    void 지원하지_않는_메서드_요청도_problem_json으로_응답한다() throws Exception {
        // given
        String path = "/probe/business";

        // when
        ResultActions result = mockMvc.perform(post(path));

        // then
        result.andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 읽을_수_없는_요청_본문은_400과_INVALID_REQUEST로_응답한다() throws Exception {
        // given
        String malformedJson = "{ not-json";

        // when
        ResultActions result =
                mockMvc.perform(
                        post("/probe/body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(malformedJson));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 존재하지_않는_경로는_404와_NOT_FOUND로_응답한다() throws Exception {
        // given
        String path = "/probe/missing";

        // when
        ResultActions result = mockMvc.perform(get(path));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/business")
        String business() {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "subscription 7 not found");
        }

        @GetMapping("/probe/unexpected")
        String unexpected() {
            throw new IllegalStateException("secret-detail");
        }

        @PostMapping("/probe/body")
        String body(@RequestBody Map<String, String> payload) {
            return payload.toString();
        }
    }
}
