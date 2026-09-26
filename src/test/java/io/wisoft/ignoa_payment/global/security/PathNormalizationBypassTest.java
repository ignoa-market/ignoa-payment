package io.wisoft.ignoa_payment.global.security;

import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

// MockMvc는 서블릿 컨테이너의 경로 처리를 거치지 않으므로 실제 포트로 요청한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "ignoa.cloudfront-origin.enabled=true",
        "ignoa.cloudfront-origin.secret=cf-secret"
})
class PathNormalizationBypassTest extends IntegrationTestSupport {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private int get(String rawPath) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int post(String rawPath) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"trade_id\":1,\"amount\":1000,\"order_name\":\"x\"}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void 매트릭스_파라미터로_내부_경로를_감싸도_401이다() throws Exception {
        assertThat(get("/internal;x=1/payments/IGN-none")).isEqualTo(401);
    }

    @Test
    void 퍼센트_인코딩으로_내부_경로를_감싸도_401이다() throws Exception {
        assertThat(get("/%69nternal/payments/IGN-none")).isEqualTo(401);
        assertThat(post("/%69nternal/payments")).isEqualTo(401);
    }

    @Test
    void 퍼센트_인코딩으로_웹훅_경로를_감싸도_오리진_검사를_받는다() throws Exception {
        assertThat(post("/%70ayments/webhook")).isEqualTo(403);
    }
}
