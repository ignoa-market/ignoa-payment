package io.wisoft.ignoa_payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 운영은 scripts/schema/*.sql로 테이블을 만들고 ddl-auto: validate로 기동한다.
// 같은 구성으로 기동해, SQL과 엔티티가 어긋나면(컬럼·타입 누락) 여기서 실패하게 한다.
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers
class SchemaValidationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCopyFileToContainer(MountableFile.forHostPath("scripts/schema"), "/docker-entrypoint-initdb.d");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 스키마_SQL로_만든_DB에서_엔티티_검증을_통과한다() {
        // 컨텍스트가 떴다는 것 자체가 validate 통과다.
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void 이중_결제를_막는_유니크_제약이_있다() {
        // validate는 유니크 제약을 검사하지 않으므로 직접 확인한다.
        List<String> uniqueColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'payment' AND non_unique = 0
                """, String.class);

        assertThat(uniqueColumns).contains("paid_trade_id", "order_id", "payment_key");
    }
}
