package io.wisoft.ignoa_payment.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.wisoft.ignoa_payment.callback.client.ApiCallbackClient;
import io.wisoft.ignoa_payment.toss.TossClient;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestSupport {

    protected static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";
    protected static final String INTERNAL_KEY = "test-internal-key";

    @ServiceConnection
    static final MySQLContainer<?> MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.0");

    static {
        MYSQL_CONTAINER.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    // 모든 통합 테스트에서 실제 Toss 호출을 막는다.
    @MockitoBean
    protected TossClient tossClient;

    // 모든 통합 테스트에서 실제 api 호출을 막는다.
    @MockitoBean
    protected ApiCallbackClient apiCallbackClient;

    @AfterEach
    void cleanUpDatabase() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'shedlock'
                """, String.class);
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        tables.forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`"));
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}
