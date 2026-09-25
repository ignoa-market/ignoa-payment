package io.wisoft.ignoa_payment.global.security;

import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalApiKeyFilterTest extends IntegrationTestSupport {

    @Test
    void 올바른_키면_통과한다() throws Exception {
        mockMvc.perform(get("/internal/probe").header(INTERNAL_KEY_HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void 키가_없으면_401이다() throws Exception {
        mockMvc.perform(get("/internal/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_API_KEY"));
    }

    @Test
    void 키가_빈_문자열이면_401이다() throws Exception {
        mockMvc.perform(get("/internal/probe").header(INTERNAL_KEY_HEADER, ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 키가_다르면_401이다() throws Exception {
        mockMvc.perform(get("/internal/probe").header(INTERNAL_KEY_HEADER, "wrong-key-with-different-length"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 내부_경로가_아니면_키_없이_통과한다() throws Exception {
        mockMvc.perform(post("/payments/probe"))
                .andExpect(status().isOk());
    }
}
