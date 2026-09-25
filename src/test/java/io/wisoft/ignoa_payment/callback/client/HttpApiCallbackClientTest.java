package io.wisoft.ignoa_payment.callback.client;

import io.wisoft.ignoa_payment.callback.service.CallbackOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpApiCallbackClientTest {

    private static final String BASE_URL = "http://api.local";
    private static final String URL = BASE_URL + "/internal/trades/10/payment-result";

    private MockRestServiceServer server;
    private HttpApiCallbackClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpApiCallbackClient(builder.build());
    }

    @Test
    void 저장된_본문을_그대로_보내고_2xx면_SUCCESS다() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"trade_id\":10,\"status\":\"DONE\"}"))
                .andRespond(withSuccess());

        CallbackOutcome outcome = client.send(10L, "{\"trade_id\":10,\"status\":\"DONE\"}");

        assertThat(outcome.type()).isEqualTo(CallbackOutcome.Type.SUCCESS);
    }

    @Test
    void 일반_4xx는_PERMANENT다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.send(10L, "{}").type()).isEqualTo(CallbackOutcome.Type.PERMANENT);
    }

    @Test
    void 요청시간초과_408과_429와_5xx는_RETRYABLE이다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(client.send(10L, "{}").type()).isEqualTo(CallbackOutcome.Type.RETRYABLE);
        assertThat(client.send(10L, "{}").type()).isEqualTo(CallbackOutcome.Type.RETRYABLE);
        assertThat(client.send(10L, "{}").type()).isEqualTo(CallbackOutcome.Type.RETRYABLE);
    }

    @Test
    void 타임아웃은_RETRYABLE이다() {
        ResponseCreator timeout = request -> {
            throw new SocketTimeoutException("Read timed out");
        };
        server.expect(requestTo(URL)).andRespond(timeout);

        CallbackOutcome outcome = client.send(10L, "{}");

        assertThat(outcome.type()).isEqualTo(CallbackOutcome.Type.RETRYABLE);
        assertThat(outcome.error()).isNotBlank();
    }
}
