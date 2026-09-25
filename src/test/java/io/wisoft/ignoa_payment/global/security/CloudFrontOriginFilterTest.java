package io.wisoft.ignoa_payment.global.security;

import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "ignoa.cloudfront-origin.enabled=true",
        "ignoa.cloudfront-origin.secret=cf-secret"
})
class CloudFrontOriginFilterTest extends IntegrationTestSupport {

    @Test
    void 오리진_헤더가_맞으면_통과한다() throws Exception {
        mockMvc.perform(post("/payments/probe").header("X-Origin-Verify", "cf-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void 오리진_헤더가_없으면_403이다() throws Exception {
        mockMvc.perform(post("/payments/probe"))
                .andExpect(status().isForbidden());
    }
}
