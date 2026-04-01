package dev.nklip.javacraft.soap2rest.soap.cucumber.step;

import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSResponse;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.KeyValuesType;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrder;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Map;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.*;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSRequest.Body;
import dev.nklip.javacraft.soap2rest.soap.service.order.GasService;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.web.bind.annotation.RequestMethod;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Scope(SCOPE_CUCUMBER_GLUE)
public class GasServiceStepDefinitions extends BaseSoapStep {

    private static final String SUCCESS_CODE = "200";

    @LocalServerPort
    int port;

    @When("user {string} deletes all previous gas metrics")
    public void syncUserDeletesAllPreviousGasMetrics(String accountId) throws Exception {
        DSResponse dsResponse = sendServiceOrder(createServiceOrder(RequestMethod.DELETE.toString(), accountId));
        assertStatusAndResult(dsResponse, SUCCESS_CODE, "true");
    }

    @Then("user {string} has no latest gas metric")
    public void syncUserHasNoLatestGasMetric(String accountId) throws Exception {
        ServiceOrder serviceOrder = createServiceOrder(RequestMethod.GET.toString(), accountId);
        addParam(serviceOrder, "path", "/latest");

        DSResponse dsResponse = sendServiceOrder(serviceOrder);
        assertStatusCode(dsResponse, SUCCESS_CODE);
        Assertions.assertNull(getStatusResult(dsResponse));
    }

    @When("user {string} puts gas metric id {string} meter {string} reading {string} date {string}")
    public void syncUserPutsGasMetric(
            String accountId, String metricId, String meterId, String reading, String date) throws Exception {
        ServiceOrder serviceOrder = createServiceOrder(RequestMethod.PUT.toString(), accountId);
        addParam(serviceOrder, "meterId", meterId);
        addParam(serviceOrder, "reading", reading);
        addParam(serviceOrder, "date", date);

        DSResponse dsResponse = sendServiceOrder(serviceOrder);
        assertStatusAndResult(dsResponse, SUCCESS_CODE, toMetricResult(metricId, meterId, reading, date));
    }

    @When("user {string} puts gas metrics")
    public void userPutsGasMetrics(String accountId, DataTable table) throws Exception {
        for (Map<String, String> row : table.asMaps(String.class, String.class)) {
            syncUserPutsGasMetric(
                    accountId,
                    row.get("id"),
                    row.get("meterId"),
                    row.get("reading"),
                    row.get("date")
            );
        }
    }

    @Then("user {string} gets latest gas metric id {string} meter {string} reading {string} date {string}")
    public void syncUserGetsLatestGasMetric(
            String accountId, String metricId, String meterId, String reading, String date) throws Exception {
        ServiceOrder serviceOrder = createServiceOrder(RequestMethod.GET.toString(), accountId);
        addParam(serviceOrder, "path", "/latest");

        DSResponse dsResponse = sendServiceOrder(serviceOrder);
        assertStatusAndResult(dsResponse, SUCCESS_CODE, toMetricResult(metricId, meterId, reading, date));
    }

    @Then("user {string} has gas metrics list size {string}")
    public void userHasGasMetricsListSize(String accountId, String expectedSize) throws Exception {
        ServiceOrder serviceOrder = createServiceOrder(RequestMethod.GET.toString(), accountId);
        addParam(serviceOrder, "path", "");

        DSResponse dsResponse = sendServiceOrder(serviceOrder);
        assertStatusCode(dsResponse, SUCCESS_CODE);

        int actualSize = toMetricListSize(getStatusResult(dsResponse));
        Assertions.assertEquals(Integer.parseInt(expectedSize), actualSize);
    }

    ServiceOrder createServiceOrder(String serviceType, String accountId) {
        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setServiceName(GasService.class.getSimpleName());
        serviceOrder.setServiceType(serviceType);
        serviceOrder.setServiceOrderID(accountId);
        return serviceOrder;
    }

    void addParam(ServiceOrder serviceOrder, String key, String value) {
        KeyValuesType keyValuesType = new KeyValuesType();
        keyValuesType.setKey(key);
        keyValuesType.setValue(value);
        serviceOrder.getParams().add(keyValuesType);
    }

    DSResponse sendServiceOrder(ServiceOrder serviceOrder) throws Exception {
        Body body = new Body();
        body.setServiceOrder(serviceOrder);
        return sendSoapRequest(port, body);
    }

    void assertStatusAndResult(DSResponse dsResponse, String expectedCode, String expectedResult) {
        assertStatusCode(dsResponse, expectedCode);
        Assertions.assertEquals(expectedResult, getStatusResult(dsResponse));
    }

    void assertStatusCode(DSResponse dsResponse, String expectedCode) {
        Assertions.assertNotNull(dsResponse);
        Assertions.assertEquals(expectedCode, dsResponse.getBody().getServiceOrderStatus().getStatusType().getCode());
    }

    String getStatusResult(DSResponse dsResponse) {
        return dsResponse.getBody().getServiceOrderStatus().getStatusType().getResult();
    }

    String toMetricResult(String metricId, String meterId, String reading, String date) {
        return "Metric(id=%s, meterId=%s, reading=%s, date=%s, usageSinceLastRead=null, periodSinceLastRead=null, avgDailyUsage=null)"
                .formatted(metricId, meterId, reading, date);
    }

    int toMetricListSize(String statusResult) {
        if (statusResult == null || statusResult.isBlank() || statusResult.equals("[]")) {
            return 0;
        }

        int count = 0;
        int index = 0;
        while ((index = statusResult.indexOf("{id=", index)) >= 0) {
            count++;
            index += 4;
        }
        return count;
    }

}
