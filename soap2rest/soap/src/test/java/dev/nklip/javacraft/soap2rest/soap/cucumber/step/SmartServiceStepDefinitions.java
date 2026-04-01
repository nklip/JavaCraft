package dev.nklip.javacraft.soap2rest.soap.cucumber.step;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSRequest.Body;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSResponse;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.KeyValuesType;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrder;
import dev.nklip.javacraft.soap2rest.soap.service.order.SmartService;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.web.bind.annotation.RequestMethod;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Scope(SCOPE_CUCUMBER_GLUE)
public class SmartServiceStepDefinitions extends BaseSoapStep {

    private static final String SUCCESS_CODE = "200";
    private static final String ACCEPTED_CODE = "202";
    private static final String ASYNC_POLL_PATH = "/async";

    @LocalServerPort
    int port;

    private String lastAsyncRequestId;
    private DSResponse lastAsyncPollResponse;

    @When("user {string} deletes all previous smart metrics")
    public void userDeletesAllPreviousSmartMetrics(String accountId) throws Exception {
        DSResponse dsResponse = sendServiceOrder(createServiceOrder(RequestMethod.DELETE.toString(), accountId));
        assertStatusAndResult(dsResponse, SUCCESS_CODE, "true");
    }

    @Then("user {string} has no latest smart metrics")
    public void userHasNoLatestSmartMetrics(String accountId) throws Exception {
        ServiceOrder serviceOrder = createServiceOrder(RequestMethod.GET.toString(), accountId);
        addParam(serviceOrder, "path", "/latest");

        DSResponse dsResponse = sendServiceOrder(serviceOrder);
        assertStatusCode(dsResponse, SUCCESS_CODE);
        Assertions.assertNull(getStatusResult(dsResponse));
    }

    @When("user {string} asynchronously puts smart metrics")
    public void userAsynchronouslyPutsSmartMetrics(String accountId, DataTable table) throws Exception {
        ServiceOrder serviceOrder = createTypedMetricsServiceOrder(accountId, table);
        submitAsyncSmartMetrics(serviceOrder);
    }

    @Then("user receives an async smart tracking id")
    public void userReceivesAnAsyncSmartTrackingId() {
        Assertions.assertNotNull(lastAsyncRequestId);
        Assertions.assertFalse(lastAsyncRequestId.isBlank());
    }

    @When("user synchronously polls the last async smart request")
    public void userSynchronouslyPollsTheLastAsyncSmartRequest() throws Exception {
        Assertions.assertNotNull(lastAsyncRequestId);
        Assertions.assertFalse(lastAsyncRequestId.isBlank());

        ServiceOrder serviceOrder = createServiceOrder(RequestMethod.GET.toString(), lastAsyncRequestId);
        addParam(serviceOrder, "path", ASYNC_POLL_PATH);

        lastAsyncPollResponse = sendServiceOrder(serviceOrder, false);
    }

    @Then("last async smart request is still pending")
    public void lastAsyncSmartRequestIsStillPending() {
        Assertions.assertNotNull(lastAsyncPollResponse);
        assertStatusCode(lastAsyncPollResponse, ACCEPTED_CODE);
        Assertions.assertEquals(lastAsyncRequestId, getServiceOrderId(lastAsyncPollResponse));
        Assertions.assertEquals("Accepted for asynchronous processing", getStatusDesc(lastAsyncPollResponse));
    }

    @Then("last async smart request is completed with result {string}")
    public void lastAsyncSmartRequestIsCompletedWithResult(String expectedResult) {
        Assertions.assertNotNull(lastAsyncPollResponse);
        assertStatusCode(lastAsyncPollResponse, SUCCESS_CODE);
        Assertions.assertEquals(lastAsyncRequestId, getServiceOrderId(lastAsyncPollResponse));
        Assertions.assertEquals(expectedResult, getStatusResult(lastAsyncPollResponse));
    }

    @Then("last async smart request fails with description {string}")
    public void lastAsyncSmartRequestFailsWithDescription(String expectedDescription) {
        Assertions.assertNotNull(lastAsyncPollResponse);
        assertStatusCode(lastAsyncPollResponse, "500");
        Assertions.assertEquals(lastAsyncRequestId, getServiceOrderId(lastAsyncPollResponse));
        Assertions.assertEquals(expectedDescription, getStatusDesc(lastAsyncPollResponse));
    }

    @When("user {string} puts smart metrics")
    public void userPutsSmartMetrics(String accountId, DataTable table) throws Exception {
        if (isTypedMetricsTable(table)) {
            for (ServiceOrder serviceOrder : createTypedMetricsServiceOrders(accountId, table)) {
                submitSyncSmartMetrics(serviceOrder);
            }
            return;
        }

        for (Map<String, String> row : toRowMaps(table)) {
            submitSyncSmartMetrics(createWideMetricsServiceOrder(accountId, row));
        }
    }

    @Then("user {string} gets latest smart metrics")
    public void userGetsLatestSmartMetrics(String accountId, DataTable table) throws Exception {
        ServiceOrder serviceOrder = createServiceOrder(RequestMethod.GET.toString(), accountId);
        addParam(serviceOrder, "path", "/latest");

        DSResponse dsResponse = sendServiceOrder(serviceOrder, false);
        List<Map<String, String>> rows = toRowMaps(table);
        assertStatusAndResult(
                dsResponse,
                SUCCESS_CODE,
                toMetricsResult(
                        accountId,
                        toExpectedMetricResult(rows, "gas"),
                        toExpectedMetricResult(rows, "ele")
                )
        );
    }

    @Then("user {string} has smart metrics list size {string}")
    public void userHasSmartMetricsListSize(String accountId, String expectedSize) throws Exception {
        ServiceOrder serviceOrder = createServiceOrder(RequestMethod.GET.toString(), accountId);
        addParam(serviceOrder, "path", "");

        DSResponse dsResponse = sendServiceOrder(serviceOrder, false);
        assertStatusCode(dsResponse, SUCCESS_CODE);

        int expected = Integer.parseInt(expectedSize);
        String statusResult = getStatusResult(dsResponse);
        Assertions.assertEquals(expected, toMetricsListSize(statusResult, "gasReadings=[", "], elecReadings=["));
        Assertions.assertEquals(expected, toMetricsListSize(statusResult, "elecReadings=[", "])"));
    }

    ServiceOrder createServiceOrder(String serviceType, String accountId) {
        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setServiceName(SmartService.class.getSimpleName());
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

    void addMetricParam(
            ServiceOrder serviceOrder, String key, String id, String meterId, String reading, String date) {
        addParam(serviceOrder, key, toMetricJson(id, meterId, reading, date));
    }

    void addTypedMetricRow(ServiceOrder serviceOrder, Map<String, String> row) {
        String type = row.get("type");
        String id = row.get("id");
        String meterId = row.get("meterId");
        String reading = row.get("reading");
        String date = row.get("date");

        Assertions.assertNotNull(type);
        Assertions.assertNotNull(meterId);
        Assertions.assertNotNull(reading);
        Assertions.assertNotNull(date);

        addMetricParam(serviceOrder, toMetricKey(type), id, meterId, reading, date);
    }

    boolean isTypedMetricsTable(DataTable table) {
        return !table.cells().isEmpty()
                && table.cells().getFirst().containsAll(java.util.List.of("type", "meterId", "reading", "date"));
    }

    ServiceOrder createTypedMetricsServiceOrder(String accountId, DataTable table) {
        List<ServiceOrder> serviceOrders = createTypedMetricsServiceOrders(accountId, table);
        if (serviceOrders.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one smart metrics request but got " + serviceOrders.size());
        }
        return serviceOrders.getFirst();
    }

    List<ServiceOrder> createTypedMetricsServiceOrders(String accountId, DataTable table) {
        List<ServiceOrder> serviceOrders = new ArrayList<>();
        ServiceOrder currentServiceOrder = createServiceOrder(RequestMethod.PUT.toString(), accountId);
        boolean hasGas = false;
        boolean hasElectric = false;

        for (Map<String, String> row : toRowMaps(table)) {
            String metricKey = toMetricKey(row.get("type"));
            if ("gasMetric".equals(metricKey)) {
                if (hasGas) {
                    throw new IllegalArgumentException("Each smart metrics request can contain only one gas row");
                }
                hasGas = true;
            } else {
                if (hasElectric) {
                    throw new IllegalArgumentException("Each smart metrics request can contain only one electric row");
                }
                hasElectric = true;
            }

            addTypedMetricRow(currentServiceOrder, row);
            if (hasGas && hasElectric) {
                serviceOrders.add(currentServiceOrder);
                currentServiceOrder = createServiceOrder(RequestMethod.PUT.toString(), accountId);
                hasGas = false;
                hasElectric = false;
            }
        }

        if (hasGas || hasElectric) {
            throw new IllegalArgumentException("Each smart metrics request must contain one gas row and one electric row");
        }

        return serviceOrders;
    }

    ServiceOrder createWideMetricsServiceOrder(String accountId, Map<String, String> row) {
        ServiceOrder serviceOrder = createServiceOrder(RequestMethod.PUT.toString(), accountId);
        addMetricParam(serviceOrder, "gasMetric", row.get("gasId"), row.get("gasMeterId"), row.get("gasReading"), row.get("gasDate"));
        addMetricParam(serviceOrder, "elecMetric", row.get("elecId"), row.get("elecMeterId"), row.get("elecReading"), row.get("elecDate"));
        return serviceOrder;
    }

    void submitSyncSmartMetrics(ServiceOrder serviceOrder) throws Exception {
        DSResponse dsResponse = sendServiceOrder(serviceOrder, false);
        assertStatusAndResult(dsResponse, SUCCESS_CODE, "true");
    }

    void submitAsyncSmartMetrics(ServiceOrder serviceOrder) throws Exception {
        DSResponse dsResponse = sendServiceOrder(serviceOrder, true);
        assertStatusCode(dsResponse, ACCEPTED_CODE);
        lastAsyncRequestId = getServiceOrderId(dsResponse);
        Assertions.assertNotNull(lastAsyncRequestId);
        Assertions.assertFalse(lastAsyncRequestId.isBlank());
    }

    String toMetricKey(String type) {
        return switch (type.trim().toLowerCase()) {
            case "gas" -> "gasMetric";
            case "ele", "elec", "electric" -> "elecMetric";
            default -> throw new IllegalArgumentException("Unsupported smart metric type: " + type);
        };
    }

    List<Map<String, String>> toRowMaps(DataTable table) {
        List<List<String>> cells = table.cells();
        if (cells.isEmpty()) {
            return List.of();
        }

        List<String> headers = cells.getFirst();
        return cells.stream()
                .skip(1)
                .map(row -> toRowMap(headers, row))
                .toList();
    }

    Map<String, String> toRowMap(List<String> headers, List<String> row) {
        Assertions.assertEquals(headers.size(), row.size());

        Map<String, String> mappedRow = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            mappedRow.put(headers.get(i), row.get(i));
        }
        return mappedRow;
    }

    String toExpectedMetricResult(List<Map<String, String>> rows, String type) {
        return rows.stream()
                .filter(row -> toMetricKey(row.get("type")).equals(toMetricKey(type)))
                .findFirst()
                .map(this::toExpectedMetricResult)
                .orElseThrow(() -> new IllegalArgumentException("Missing smart metric type: " + type));
    }

    String toExpectedMetricResult(Map<String, String> row) {
        String id = row.get("id");
        String meterId = row.get("meterId");
        String reading = row.get("reading");
        String date = row.get("date");

        Assertions.assertNotNull(id);
        Assertions.assertNotNull(meterId);
        Assertions.assertNotNull(reading);
        Assertions.assertNotNull(date);

        return toMetricResult(id, meterId, reading, date);
    }

    DSResponse sendServiceOrder(ServiceOrder serviceOrder) throws Exception {
        return sendServiceOrder(serviceOrder, false);
    }

    DSResponse sendServiceOrder(ServiceOrder serviceOrder, boolean async) throws Exception {
        Body body = new Body();
        body.setServiceOrder(serviceOrder);
        body.setAsyncronousResponse(Boolean.toString(async));
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

    String getStatusDesc(DSResponse dsResponse) {
        return dsResponse.getBody().getServiceOrderStatus().getStatusType().getDesc();
    }

    String getServiceOrderId(DSResponse dsResponse) {
        return dsResponse.getBody().getServiceOrderStatus().getServiceOrderID();
    }

    String toMetricJson(String id, String meterId, String reading, String date) {
        if (id == null) {
            return "{\"meterId\":%s,\"reading\":%s,\"date\":\"%s\"}"
                    .formatted(meterId, reading, date);
        }
        return "{\"id\":%s,\"meterId\":%s,\"reading\":%s,\"date\":\"%s\"}"
                .formatted(id, meterId, reading, date);
    }

    String toMetricResult(String metricId, String meterId, String reading, String date) {
        return "Metric(id=%s, meterId=%s, reading=%s, date=%s, usageSinceLastRead=null, periodSinceLastRead=null, avgDailyUsage=null)"
                .formatted(metricId, meterId, reading, date);
    }

    String toMetricsResult(String accountId, String gasMetricResult, String electricMetricResult) {
        return "Metrics(accountId=%s, gasReadings=[%s], elecReadings=[%s])"
                .formatted(accountId, gasMetricResult, electricMetricResult);
    }

    int toMetricsListSize(String statusResult, String sectionStart, String sectionEnd) {
        if (statusResult == null || statusResult.isBlank()) {
            return 0;
        }

        int start = statusResult.indexOf(sectionStart);
        if (start < 0) {
            return 0;
        }
        start += sectionStart.length();

        int end = statusResult.indexOf(sectionEnd, start);
        if (end < 0) {
            return 0;
        }

        String section = statusResult.substring(start, end);
        if (section.isBlank()) {
            return 0;
        }

        int count = 0;
        int index = 0;
        while ((index = section.indexOf("Metric(", index)) >= 0) {
            count++;
            index += "Metric(".length();
        }
        return count;
    }
}
