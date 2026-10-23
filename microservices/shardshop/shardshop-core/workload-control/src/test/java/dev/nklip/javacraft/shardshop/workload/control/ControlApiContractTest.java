package dev.nklip.javacraft.shardshop.workload.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.common.contract.ContractDocument.Example;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static dev.nklip.javacraft.shardshop.common.contract.ContractDocument.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The control OpenAPI document, its examples, and the JSON that the resource classes actually write. */
class ControlApiContractTest {
    static final ContractDocument API = ContractDocument.openApi(Path.of("src/main/resources/contracts/openapi.yaml"));
    private static final String SCHEMAS = "#/components/schemas/";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void isAValidSelfContainedOpenApiDocument() {
        assertEquals(List.of(), API.structureErrors());
    }

    @Test
    void everyExampleMatchesItsSchema() {
        List<Example> examples = API.examples();
        assertFalse(examples.isEmpty(), "The document has inline examples");
        for (Example example : examples) {
            assertEquals(List.of(), API.errors(example.schema(), example.value()), example.location());
        }
    }

    @Test
    void everyResponseBodyHasAnExample() {
        assertEquals(List.of(), API.responsesWithoutExample());
    }

    @Test
    void writtenStatusesMatchTheSchemaInEveryState() throws JsonProcessingException {
        WorkloadControl.Settings seeder = new WorkloadControl.Settings("product-seeder", "HTTP requests/s",
                new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("10"), 1, List.of("creation", "verification"));
        WorkloadControl control = new WorkloadControl(seeder, WorkloadControl.Phase.IDLE);
        assertDocumented(control.status());
        control.phase(WorkloadControl.Phase.ACTIVE);
        control.run(() -> new ControlStatus.Run(2, "RUNNING", "2026-10-22T10:15:30.123Z", null, 5, 1056, null, null));
        control.traffic().sent("creation", true);
        control.traffic().failed("creation");
        control.started();
        WorkloadControl.Operation operation = control.begin();
        assertDocumented(control.pause());
        operation.close();
        assertDocumented(control.rate(new BigDecimal("0.001")));
        control.phase(WorkloadControl.Phase.COMPLETED);
        control.run(() -> new ControlStatus.Run(2, "COMPLETED", "2026-10-22T10:15:30.123Z",
                "2026-10-22T10:16:00Z", 1056, 1056, "Seeded and verified catalog-v2", null));
        assertDocumented(control.resume());
        assertDocumented(new ControlStatus("order-producer", "new order starts/s", "RUNNING", BigDecimal.TEN, false,
                BigDecimal.TEN, new BigDecimal("0.5"), new BigDecimal("100"), new BigDecimal("0.1"), new BigDecimal("0.812"),
                3, 1, 9, 0, List.of(new ControlStatus.TrafficCategory("startup", 9, 0, 0)),
                new ControlStatus.Limit("CONCURRENCY", 1, new BigDecimal("1.231"), new BigDecimal("0.812")), null, new ControlStatus.Producer(4, 2, 100, 100, true, 9, 7, 1,
                        new ControlStatus.Latency(8, BigDecimal.TEN, new BigDecimal("19.2")),
                        new ControlStatus.Latency(8, new BigDecimal("30"), new BigDecimal("42"))), 4));
    }

    @Test
    void writtenErrorsMatchTheSchema() throws JsonProcessingException {
        ControlFailureMapper mapper = new ControlFailureMapper();
        for (ControlFailure.Reason reason : ControlFailure.Reason.values()) {
            Object error = mapper.toResponse(new ControlFailure(reason)).getEntity();
            assertEquals(List.of(), API.errors(SCHEMAS + "Error", json(JSON.writeValueAsString(error))), reason.name());
        }
    }

    @Test
    void rateUpdatesAreOneNonnegativeNumber() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "RateUpdate",
                List.of(json("{\"rate\": 0}"), json("{\"rate\": 0.5}"), json("{\"rate\": 10000}")),
                List.of(json("{}"), json("{\"rate\": -1}"), json("{\"rate\": \"5\"}"), json("{\"rate\": 10001}"),
                        json("{\"rate\": 1, \"other\": 1}"), json("{\"rate\": null}"))));
    }

    private static void assertDocumented(ControlStatus status) throws JsonProcessingException {
        String written = JSON.writeValueAsString(status);
        assertEquals(List.of(), API.errors(SCHEMAS + "ControlStatus", json(written)), written);
    }
}
