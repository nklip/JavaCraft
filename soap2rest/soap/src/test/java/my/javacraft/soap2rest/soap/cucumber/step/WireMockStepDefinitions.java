package my.javacraft.soap2rest.soap.cucumber.step;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.cucumber.java.en.Given;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.soap2rest.soap.service.RestAppEndpoints;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
public class WireMockStepDefinitions {

    private static final String FIRST_METRIC_ADDED = "FIRST_METRIC_ADDED";
    private static final String SECOND_METRIC_ADDED = "SECOND_METRIC_ADDED";
    private static final String THIRD_METRIC_ADDED = "THIRD_METRIC_ADDED";
    private static final String ASYNC_PENDING = "ASYNC_PENDING";
    private static final String GAS_METRIC_TEMPLATE = "gas/metric.json.hbs";
    private static final String GAS_METRICS_TEMPLATE = "gas/metrics.json.hbs";
    private static final String ELECTRIC_METRIC_TEMPLATE = "electric/metric.json.hbs";
    private static final String ELECTRIC_METRICS_TEMPLATE = "electric/metrics.json.hbs";
    private static final String SMART_METRICS_TEMPLATE = "smart/metrics.json.hbs";

    @Value("${rest-app.port}")
    int restPort;

    private static final Object LOCK = new Object();
    private static WireMockServer wireMockServer;

    @Given("we start WireMock server")
    public void setup () {
        synchronized (LOCK) {
            try {
                if (wireMockServer == null) {
                    wireMockServer = new WireMockServer(
                            options().port(restPort).templatingEnabled(true).globalTemplating(true)
                    );
                }

                if (!wireMockServer.isRunning()) {
                    wireMockServer.start();
                } else {
                    wireMockServer.resetAll();
                }

                wireMockServer.resetScenarios();

                addGasStubs(wireMockServer);
                addElectricStubs(wireMockServer);
                addSmartStubs(wireMockServer);

                log.info("Setup Stubs is done for MetricService");
            } catch (Exception e) {
                throw new IllegalStateException(
                        "WireMock setup failed on rest-app.port=%s.".formatted(restPort) +
                        " Stop any process that occupies this port or override rest-app.port for tests.",
                        e
                );
            }
        }
    }

    void addGasStubs(WireMockServer wireMockServer) {
        addGasStubsForAccount(
                wireMockServer,
                "1",
                List.of(
                        metric(23, 200, "2536.708", "2023-07-28"),
                        metric(24, 200, "2601.125", "2023-07-29"),
                        metric(25, 200, "2710.900", "2023-07-30")
                )
        );

        addGasStubsForAccount(
                wireMockServer,
                "2",
                List.of(
                        metric(31, 300, "100.111", "2024-02-10"),
                        metric(32, 300, "101.222", "2024-02-11"),
                        metric(33, 300, "105.555", "2024-02-12")
                )
        );
    }

    void addGasStubsForAccount(
            WireMockServer wireMockServer,
            String accountId,
            List<Map<String, Object>> metrics
    ) {
        String scenarioName = "gas-flow-account-" + accountId;
        String baseUrl = RestAppEndpoints.gas(accountId);

        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, STARTED);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, FIRST_METRIC_ADDED);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, SECOND_METRIC_ADDED);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, THIRD_METRIC_ADDED);

        wireMockServer.stubFor(put(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willSetStateTo(FIRST_METRIC_ADDED)
                .willReturn(withTemplateMetric(GAS_METRIC_TEMPLATE, metrics.getFirst()))
        );

        wireMockServer.stubFor(put(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(FIRST_METRIC_ADDED)
                .willSetStateTo(SECOND_METRIC_ADDED)
                .willReturn(withTemplateMetric(GAS_METRIC_TEMPLATE, metrics.get(1)))
        );

        wireMockServer.stubFor(put(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(SECOND_METRIC_ADDED)
                .willSetStateTo(THIRD_METRIC_ADDED)
                .willReturn(withTemplateMetric(GAS_METRIC_TEMPLATE, metrics.get(2)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withStatus(200))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(FIRST_METRIC_ADDED)
                .willReturn(withTemplateMetric(GAS_METRIC_TEMPLATE, metrics.getFirst()))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(SECOND_METRIC_ADDED)
                .willReturn(withTemplateMetric(GAS_METRIC_TEMPLATE, metrics.get(1)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(THIRD_METRIC_ADDED)
                .willReturn(withTemplateMetric(GAS_METRIC_TEMPLATE, metrics.get(2)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willReturn(withTemplateMetrics(GAS_METRICS_TEMPLATE, List.of()))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(FIRST_METRIC_ADDED)
                .willReturn(withTemplateMetrics(GAS_METRICS_TEMPLATE, metrics.subList(0, 1)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(SECOND_METRIC_ADDED)
                .willReturn(withTemplateMetrics(GAS_METRICS_TEMPLATE, metrics.subList(0, 2)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(THIRD_METRIC_ADDED)
                .willReturn(withTemplateMetrics(GAS_METRICS_TEMPLATE, metrics))
        );
    }

    void addElectricStubs(WireMockServer wireMockServer) {
        addElectricStubsForAccount(
                wireMockServer,
                "1",
                List.of(
                        metric(13, 100, "678.439", "2023-07-28"),
                        metric(14, 100, "700.111", "2023-07-29"),
                        metric(15, 100, "720.333", "2023-07-30")
                )
        );

        addElectricStubsForAccount(
                wireMockServer,
                "2",
                List.of(
                        metric(21, 220, "54.321", "2024-01-15"),
                        metric(22, 220, "60.999", "2024-01-16"),
                        metric(23, 220, "61.222", "2024-01-17")
                )
        );
    }

    void addElectricStubsForAccount(
            WireMockServer wireMockServer,
            String accountId,
            List<Map<String, Object>> metrics
    ) {
        String scenarioName = "electric-flow-account-" + accountId;
        String baseUrl = RestAppEndpoints.electric(accountId);

        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, STARTED);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, FIRST_METRIC_ADDED);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, SECOND_METRIC_ADDED);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, THIRD_METRIC_ADDED);

        wireMockServer.stubFor(put(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willSetStateTo(FIRST_METRIC_ADDED)
                .willReturn(withTemplateMetric(ELECTRIC_METRIC_TEMPLATE, metrics.getFirst()))
        );

        wireMockServer.stubFor(put(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(FIRST_METRIC_ADDED)
                .willSetStateTo(SECOND_METRIC_ADDED)
                .willReturn(withTemplateMetric(ELECTRIC_METRIC_TEMPLATE, metrics.get(1)))
        );

        wireMockServer.stubFor(put(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(SECOND_METRIC_ADDED)
                .willSetStateTo(THIRD_METRIC_ADDED)
                .willReturn(withTemplateMetric(ELECTRIC_METRIC_TEMPLATE, metrics.get(2)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withStatus(200))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(FIRST_METRIC_ADDED)
                .willReturn(withTemplateMetric(ELECTRIC_METRIC_TEMPLATE, metrics.getFirst()))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(SECOND_METRIC_ADDED)
                .willReturn(withTemplateMetric(ELECTRIC_METRIC_TEMPLATE, metrics.get(1)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(THIRD_METRIC_ADDED)
                .willReturn(withTemplateMetric(ELECTRIC_METRIC_TEMPLATE, metrics.get(2)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willReturn(withTemplateMetrics(ELECTRIC_METRICS_TEMPLATE, List.of()))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(FIRST_METRIC_ADDED)
                .willReturn(withTemplateMetrics(ELECTRIC_METRICS_TEMPLATE, metrics.subList(0, 1)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(SECOND_METRIC_ADDED)
                .willReturn(withTemplateMetrics(ELECTRIC_METRICS_TEMPLATE, metrics.subList(0, 2)))
        );

        wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(THIRD_METRIC_ADDED)
                .willReturn(withTemplateMetrics(ELECTRIC_METRICS_TEMPLATE, metrics))
        );
    }

    Map<String, Object> metric(int id, int meterId, String reading, String date) {
        return Map.of(
                "id", id,
                "meterId", meterId,
                "reading", reading,
                "date", date
        );
    }

    com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder withTemplateMetric(
            String templatePath, Map<String, Object> metric) {
        return aResponse()
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withStatus(200)
                .withBodyFile(templatePath)
                .withTransformers("response-template")
                .withTransformerParameter("metric", metric);
    }

    com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder withTemplateMetrics(
            String templatePath, List<Map<String, Object>> metrics) {
        return aResponse()
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withStatus(200)
                .withBodyFile(templatePath)
                .withTransformers("response-template")
                .withTransformerParameter("metrics", metrics);
    }

    void addDeleteStubForState(
            WireMockServer wireMockServer, String scenarioName, String baseUrl, String state) {
        wireMockServer.stubFor(delete(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(state)
                .willSetStateTo(STARTED)
                .willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withStatus(200)
                        .withBody("true"))
        );
    }

    void addSmartStubs(WireMockServer wireMockServer) {
        addSmartStubsForAccount(
                wireMockServer,
                "1",
                List.of(
                        metric(23, 200, "2531.111", "2023-07-28"),
                        metric(24, 200, "2537.777", "2023-07-29"),
                        metric(25, 200, "2600.001", "2023-07-30")
                ),
                List.of(
                        metric(13, 100, "674.444", "2023-07-28"),
                        metric(14, 100, "678.888", "2023-07-29"),
                        metric(15, 100, "699.111", "2023-07-30")
                )
        );

        addSmartStubsForAccount(
                wireMockServer,
                "2",
                List.of(
                        metric(31, 300, "100.111", "2024-02-10"),
                        metric(32, 300, "101.222", "2024-02-11"),
                        metric(33, 300, "105.555", "2024-02-12")
                ),
                List.of(
                        metric(41, 400, "10.250", "2024-02-10"),
                        metric(42, 400, "10.750", "2024-02-11"),
                        metric(43, 400, "11.900", "2024-02-12")
                )
        );

        addSmartStubsForAccount(
                wireMockServer,
                "3",
                List.of(metric(53, 500, "3333.111", "2024-03-01")),
                List.of(metric(63, 600, "444.222", "2024-03-01"))
        );
        addAsyncSmartStubsForAccount(
                wireMockServer,
                "3",
                "smart-async-3-1"
        );

        addSmartStubsForAccount(
                wireMockServer,
                "4",
                List.of(),
                List.of()
        );
        addFailingAsyncSmartStubsForAccount(
                wireMockServer,
                "4",
                "smart-async-4-1"
        );
    }

    void addSmartStubsForAccount(
            WireMockServer wireMockServer,
            String accountId,
            List<Map<String, Object>> gasMetrics,
            List<Map<String, Object>> electricMetrics
    ) {
        String scenarioName = "smart-flow-account-" + accountId;
        String baseUrl = RestAppEndpoints.smart(accountId);

        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, STARTED);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, ASYNC_PENDING);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, FIRST_METRIC_ADDED);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, SECOND_METRIC_ADDED);
        addDeleteStubForState(wireMockServer, scenarioName, baseUrl, THIRD_METRIC_ADDED);

        if (hasAtLeast(gasMetrics, electricMetrics, 1)) {
            wireMockServer.stubFor(put(urlEqualTo(baseUrl))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(STARTED)
                    .willSetStateTo(FIRST_METRIC_ADDED)
                    .willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .withStatus(200)
                            .withBody("true"))
            );
        }

        if (hasAtLeast(gasMetrics, electricMetrics, 2)) {
            wireMockServer.stubFor(put(urlEqualTo(baseUrl))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(FIRST_METRIC_ADDED)
                    .willSetStateTo(SECOND_METRIC_ADDED)
                    .willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .withStatus(200)
                            .withBody("true"))
            );
        }

        if (hasAtLeast(gasMetrics, electricMetrics, 3)) {
            wireMockServer.stubFor(put(urlEqualTo(baseUrl))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(SECOND_METRIC_ADDED)
                    .willSetStateTo(THIRD_METRIC_ADDED)
                    .willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .withStatus(200)
                            .withBody("true"))
            );
        }

        wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withStatus(200))
        );

        if (hasAtLeast(gasMetrics, electricMetrics, 1)) {
            wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(FIRST_METRIC_ADDED)
                    .willReturn(withTemplateSmartMetrics(accountId, gasMetrics.subList(0, 1), electricMetrics.subList(0, 1)))
            );
        }

        if (hasAtLeast(gasMetrics, electricMetrics, 2)) {
            wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(SECOND_METRIC_ADDED)
                    .willReturn(withTemplateSmartMetrics(accountId, gasMetrics.subList(1, 2), electricMetrics.subList(1, 2)))
            );
        }

        if (hasAtLeast(gasMetrics, electricMetrics, 3)) {
            wireMockServer.stubFor(get(urlEqualTo(baseUrl + "/latest"))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(THIRD_METRIC_ADDED)
                    .willReturn(withTemplateSmartMetrics(accountId, gasMetrics.subList(2, 3), electricMetrics.subList(2, 3)))
            );
        }

        wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willReturn(withTemplateSmartMetrics(accountId, List.of(), List.of()))
        );

        if (hasAtLeast(gasMetrics, electricMetrics, 1)) {
            wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(FIRST_METRIC_ADDED)
                    .willReturn(withTemplateSmartMetrics(accountId, gasMetrics.subList(0, 1), electricMetrics.subList(0, 1)))
            );
        }

        if (hasAtLeast(gasMetrics, electricMetrics, 2)) {
            wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(SECOND_METRIC_ADDED)
                    .willReturn(withTemplateSmartMetrics(accountId, gasMetrics.subList(0, 2), electricMetrics.subList(0, 2)))
            );
        }

        if (hasAtLeast(gasMetrics, electricMetrics, 3)) {
            wireMockServer.stubFor(get(urlEqualTo(baseUrl))
                    .inScenario(scenarioName)
                    .whenScenarioStateIs(THIRD_METRIC_ADDED)
                    .willReturn(withTemplateSmartMetrics(accountId, gasMetrics, electricMetrics))
            );
        }
    }

    void addAsyncSmartStubsForAccount(WireMockServer wireMockServer, String accountId, String requestId) {
        String scenarioName = "smart-flow-account-" + accountId;
        String baseUrl = RestAppEndpoints.smart(accountId);
        String resultUrl = RestAppEndpoints.smartAsyncResult(requestId);

        wireMockServer.stubFor(put(urlPathEqualTo(baseUrl))
                .withQueryParam("async", equalTo("true"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willSetStateTo(ASYNC_PENDING)
                .willReturn(asyncAcceptedSubmissionResponse(requestId))
        );

        wireMockServer.stubFor(get(urlEqualTo(resultUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(ASYNC_PENDING)
                .willSetStateTo(FIRST_METRIC_ADDED)
                .willReturn(asyncAcceptedPollResponse(requestId))
        );

        wireMockServer.stubFor(get(urlEqualTo(resultUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(FIRST_METRIC_ADDED)
                .willReturn(asyncCompletedResponse(requestId))
        );
    }

    void addFailingAsyncSmartStubsForAccount(WireMockServer wireMockServer, String accountId, String requestId) {
        String scenarioName = "smart-flow-account-" + accountId;
        String baseUrl = RestAppEndpoints.smart(accountId);
        String resultUrl = RestAppEndpoints.smartAsyncResult(requestId);

        wireMockServer.stubFor(put(urlPathEqualTo(baseUrl))
                .withQueryParam("async", equalTo("true"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(STARTED)
                .willSetStateTo(ASYNC_PENDING)
                .willReturn(asyncAcceptedSubmissionResponse(requestId))
        );

        wireMockServer.stubFor(get(urlEqualTo(resultUrl))
                .inScenario(scenarioName)
                .whenScenarioStateIs(ASYNC_PENDING)
                .willSetStateTo(STARTED)
                .willReturn(asyncFailedResponse(requestId, "Async smart request failed"))
        );
    }

    com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder withTemplateSmartMetrics(
            String accountId,
            List<Map<String, Object>> gasMetrics,
            List<Map<String, Object>> electricMetrics
    ) {
        return aResponse()
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withStatus(200)
                .withBodyFile(SMART_METRICS_TEMPLATE)
                .withTransformers("response-template")
                .withTransformerParameter("accountId", Long.parseLong(accountId))
                .withTransformerParameter("gasMetrics", gasMetrics)
                .withTransformerParameter("electricMetrics", electricMetrics);
    }

    boolean hasAtLeast(
            List<Map<String, Object>> gasMetrics,
            List<Map<String, Object>> electricMetrics,
            int expectedSize
    ) {
        return gasMetrics.size() >= expectedSize && electricMetrics.size() >= expectedSize;
    }

    com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder asyncAcceptedSubmissionResponse(String requestId) {
        return aResponse()
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .withStatus(202)
                .withBody(requestId);
    }

    com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder asyncAcceptedPollResponse(String requestId) {
        return aResponse()
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withStatus(202)
                .withBody("{\"requestId\":\"%s\"}".formatted(requestId));
    }

    com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder asyncCompletedResponse(String requestId) {
        return aResponse()
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withStatus(200)
                .withBody("{\"requestId\":\"%s\",\"result\":true}".formatted(requestId));
    }

    com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder asyncFailedResponse(
            String requestId,
            String errorMessage
    ) {
        return aResponse()
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withStatus(500)
                .withBody("{\"requestId\":\"%s\",\"errorMessage\":\"%s\"}".formatted(requestId, errorMessage));
    }

}
