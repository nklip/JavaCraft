package dev.nklip.javacraft.ewrs.dashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = EwrsDashboardApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        // The JPA auto-configuration exclusions that used to live here are gone: ewrs-app
        // is JdbcTemplate-only and now depends on spring-boot-starter-jdbc rather than
        // spring-boot-starter-data-jpa, so there is no JPA auto-configuration to exclude.
        properties = {
                "spring.liquibase.enabled=true",
                "spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.yaml",
                "logging.level.dev.nklip.javacraft.ewrs=WARN"
        }
)
@SuppressWarnings("deprecation")
public abstract class AbstractDashboardPostgresIntegrationTest {

    static final String TC_POSTGRESQL_IMAGE = "tc.image.postgresql";

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(MavenPomPropertyResolver.resolveRequiredSystemOrPomProperty(TC_POSTGRESQL_IMAGE))
    )
            .withDatabaseName("ewrs")
            .withUsername("ewrs")
            .withPassword("ewrs");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @BeforeEach
    void resetDatabaseState() {
        jdbcTemplate.execute("truncate table work_request_projection, event_store restart identity cascade");
        jdbcTemplate.execute("alter sequence work_request_id_seq restart with 1000");
        jdbcTemplate.execute("delete from budget_projection");
        jdbcTemplate.execute("""
                insert into budget_projection (budget_code, initial_budget, reserved_amount, remaining_budget, last_updated_at)
                select budget_code, initial_budget, 0, initial_budget, current_timestamp
                from budget_reference
                order by budget_code
                """);
        jdbcTemplate.execute("""
                insert into projection_checkpoint (projection_name, last_event_store_id)
                values ('ewrs-projector', 0)
                on conflict (projection_name) do update
                set last_event_store_id = excluded.last_event_store_id
                """);
    }

    protected void updateCheckpoint(long lastEventStoreId) {
        jdbcTemplate.update("""
                update projection_checkpoint
                set last_event_store_id = ?
                where projection_name = 'ewrs-projector'
                """, lastEventStoreId);
    }

    protected void insertWorkRequestProjection(
            int requestId,
            String title,
            String priority,
            String budgetCode,
            int estimate,
            String status,
            String requestedBy,
            String lastActor,
            String reason,
            UUID lastEventId,
            Instant lastOccurredAt,
            long streamVersion
    ) {
        jdbcTemplate.update("""
                insert into work_request_projection (
                    task_id, title, priority, budget_code, estimate, status,
                    requested_by, last_actor, reason, last_event_id, last_occurred_at, stream_version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                requestId,
                title,
                priority,
                budgetCode,
                estimate,
                status,
                requestedBy,
                lastActor,
                reason,
                lastEventId,
                Timestamp.from(lastOccurredAt),
                streamVersion
        );
    }

    protected void insertEvent(
            UUID eventId,
            int requestId,
            long streamVersion,
            String eventType,
            String status,
            String payloadJson,
            String metadataJson,
            Instant occurredAt
    ) {
        jdbcTemplate.update("""
                insert into event_store (
                    event_id, task_id, stream_version, event_type, status, payload, metadata, occurred_at
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
                """,
                eventId,
                requestId,
                streamVersion,
                eventType,
                status,
                payloadJson,
                metadataJson,
                Timestamp.from(occurredAt)
        );
    }

    protected void updateBudgetProjection(String budgetCode, int reservedAmount, int remainingBudget) {
        jdbcTemplate.update("""
                update budget_projection
                set reserved_amount = ?, remaining_budget = ?, last_updated_at = current_timestamp
                where budget_code = ?
                """,
                reservedAmount,
                remainingBudget,
                budgetCode
        );
    }
}
