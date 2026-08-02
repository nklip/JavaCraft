package dev.nklip.javacraft.ewrs.testing;

import dev.nklip.javacraft.ewrs.events.EventsMonitor;
import dev.nklip.javacraft.ewrs.testing.cucumber.config.PostgresContainerInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

@AutoConfigureTestRestTemplate
@SpringBootTest(
        classes = EwrsTestingApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.dev.nklip.javacraft.ewrs=WARN",
                "spring.application.name=ewrs-testing",
                // Jackson 3 turns EnumFeature READ/WRITE_ENUMS_USING_TO_STRING on by default;
                // EventStatus.toString() is a display label, so keep the name() wire format.
                "spring.jackson.datatype.enum.write-enums-using-to-string=false",
                "spring.jackson.datatype.enum.read-enums-using-to-string=false",
                "spring.http.clients.imperative.factory=simple",
                "ewrs.scenarios.target-base-url=",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        }
)
@ContextConfiguration(initializers = PostgresContainerInitializer.class)
public abstract class AbstractEwrsTestingIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected EventsMonitor eventsMonitor;

    @BeforeEach
    void resetDatabaseState() {
        jdbcTemplate.execute("truncate table work_request_projection, event_store restart identity cascade");
        jdbcTemplate.execute("alter sequence work_request_id_seq restart with 1000");
        jdbcTemplate.execute("update projection_checkpoint set last_event_store_id = 0 where projection_name = 'ewrs-projector'");
        jdbcTemplate.execute("delete from budget_projection");
        jdbcTemplate.execute("""
                insert into budget_projection (budget_code, initial_budget, reserved_amount, remaining_budget, last_updated_at)
                select budget_code, initial_budget, 0, initial_budget, current_timestamp
                from budget_reference
                order by budget_code
                """);
        eventsMonitor.clear();
    }
}
