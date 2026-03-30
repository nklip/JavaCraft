package my.javacraft.soap2rest.soap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import my.javacraft.soap2rest.rest.api.AsyncJobResultResponse;
import my.javacraft.soap2rest.rest.api.Metrics;
import my.javacraft.soap2rest.soap.generated.ds.ws.DSRequest;
import my.javacraft.soap2rest.soap.generated.ds.ws.DSResponse;
import my.javacraft.soap2rest.soap.generated.ds.ws.KeyValuesType;
import my.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.bind.annotation.RequestMethod;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncServiceTest {

    @Mock
    private HttpCallService httpCallService;

    @Mock
    private my.javacraft.soap2rest.soap.service.order.SmartService smartOrderService;

    private final DSRequestService dsRequestService = new DSRequestService();

    @Test
    void testAsyncProcessShouldReturn501ForUnsupportedService() throws Exception {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createSubmissionRequest("123", "GasService", RequestMethod.PUT.name());

        DSResponse response = asyncService.asyncProcess(dsRequest);

        Assertions.assertEquals("501", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals(
                "Async is implemented only for SmartService PUT",
                response.getBody().getServiceOrderStatus().getStatusType().getDesc()
        );
        verify(httpCallService, never()).put(any(), eq(String.class), any());
    }

    @Test
    void testAsyncProcessShouldReturnAcceptedResponseImmediately() throws Exception {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createSubmissionRequest("123", "SmartService", RequestMethod.PUT.name());
        Metrics metrics = new Metrics();
        when(smartOrderService.toMetrics(any())).thenReturn(metrics);
        when(httpCallService.put(eq(RestAppEndpoints.smartAsync("123")), eq(String.class), eq(metrics)))
                .thenReturn(ResponseEntity.accepted().body("req-1"));

        DSResponse response = asyncService.asyncProcess(dsRequest);

        Assertions.assertEquals("202", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals(
                "Accepted for asynchronous processing",
                response.getBody().getServiceOrderStatus().getStatusType().getDesc()
        );
        Assertions.assertEquals("req-1", response.getBody().getServiceOrderStatus().getServiceOrderID());
        Assertions.assertNull(response.getBody().getServiceOrderStatus().getStatusType().getResult());
        Assertions.assertEquals(123L, metrics.getAccountId());
        verify(httpCallService, never()).get(any(), eq(AsyncJobResultResponse.class));
    }

    @Test
    void testAsyncProcessShouldReturnStatusWhenRestAppDidNotAcceptRequest() throws Exception {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createSubmissionRequest("123", "SmartService", RequestMethod.PUT.name());
        when(smartOrderService.toMetrics(any())).thenReturn(new Metrics());
        when(httpCallService.put(eq(RestAppEndpoints.smartAsync("123")), eq(String.class), any(Metrics.class)))
                .thenReturn(ResponseEntity.ok().build());

        DSResponse response = asyncService.asyncProcess(dsRequest);

        Assertions.assertEquals("200", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals("Async smart request was not accepted", response.getBody().getServiceOrderStatus().getStatusType().getDesc());
    }

    @Test
    void testAsyncProcessShouldReturnErrorWhenRequestIdIsMissing() throws Exception {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createSubmissionRequest("123", "SmartService", RequestMethod.PUT.name());
        when(smartOrderService.toMetrics(any())).thenReturn(new Metrics());
        when(httpCallService.put(eq(RestAppEndpoints.smartAsync("123")), eq(String.class), any(Metrics.class)))
                .thenReturn(ResponseEntity.accepted().body(" "));

        DSResponse response = asyncService.asyncProcess(dsRequest);

        Assertions.assertEquals("500", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals("Async smart request id is missing", response.getBody().getServiceOrderStatus().getStatusType().getDesc());
    }

    @Test
    void testAsyncProcessShouldReturnErrorWhenSerializationFails() throws Exception {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createSubmissionRequest("123", "SmartService", RequestMethod.PUT.name());
        when(smartOrderService.toMetrics(any())).thenReturn(new Metrics());
        when(httpCallService.put(eq(RestAppEndpoints.smartAsync("123")), eq(String.class), any(Metrics.class)))
                .thenThrow(new JsonProcessingException("broken") { });

        DSResponse response = asyncService.asyncProcess(dsRequest);

        Assertions.assertEquals("500", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals("Unable to serialize async smart request", response.getBody().getServiceOrderStatus().getStatusType().getDesc());
    }

    @Test
    void testPollAsyncResultShouldReturnAcceptedResponse() {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createPollRequest("req-1");
        when(httpCallService.get(RestAppEndpoints.smartAsyncResult("req-1"), AsyncJobResultResponse.class))
                .thenReturn(ResponseEntity.accepted().body(new AsyncJobResultResponse("req-1", null, null)));

        DSResponse response = asyncService.pollAsyncResult(dsRequest);

        Assertions.assertEquals("202", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals(
                "Accepted for asynchronous processing",
                response.getBody().getServiceOrderStatus().getStatusType().getDesc()
        );
        Assertions.assertEquals("req-1", response.getBody().getServiceOrderStatus().getServiceOrderID());
        Assertions.assertNull(response.getBody().getServiceOrderStatus().getStatusType().getResult());
    }

    @Test
    void testPollAsyncResultShouldReturnCompletedResult() {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createPollRequest("req-2");
        when(httpCallService.get(RestAppEndpoints.smartAsyncResult("req-2"), AsyncJobResultResponse.class))
                .thenReturn(ResponseEntity.ok(new AsyncJobResultResponse("req-2", Boolean.TRUE, null)));

        DSResponse response = asyncService.pollAsyncResult(dsRequest);

        Assertions.assertEquals("200", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals("true", response.getBody().getServiceOrderStatus().getStatusType().getResult());
        Assertions.assertEquals("req-2", response.getBody().getServiceOrderStatus().getServiceOrderID());
    }

    @Test
    void testPollAsyncResultShouldReturnFailedResult() {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createPollRequest("req-3");
        when(httpCallService.get(RestAppEndpoints.smartAsyncResult("req-3"), AsyncJobResultResponse.class))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal Server Error",
                        HttpHeaders.EMPTY,
                        "{\"requestId\":\"req-3\",\"errorMessage\":\"boom\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                ));

        DSResponse response = asyncService.pollAsyncResult(dsRequest);

        Assertions.assertEquals("500", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals("boom", response.getBody().getServiceOrderStatus().getStatusType().getDesc());
        Assertions.assertEquals("req-3", response.getBody().getServiceOrderStatus().getServiceOrderID());
    }

    @Test
    void testPollAsyncResultShouldReturnErrorWhenResultBodyIsMissing() {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createPollRequest("req-4");
        when(httpCallService.get(RestAppEndpoints.smartAsyncResult("req-4"), AsyncJobResultResponse.class))
                .thenReturn(ResponseEntity.ok().build());

        DSResponse response = asyncService.pollAsyncResult(dsRequest);

        Assertions.assertEquals("500", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals("Async smart result body is missing", response.getBody().getServiceOrderStatus().getStatusType().getDesc());
    }

    @Test
    void testPollAsyncResultShouldReturnUnexpectedPollStatus() {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createPollRequest("req-5");
        when(httpCallService.get(RestAppEndpoints.smartAsyncResult("req-5"), AsyncJobResultResponse.class))
                .thenReturn(ResponseEntity.status(418).body(new AsyncJobResultResponse("req-5", null, null)));

        DSResponse response = asyncService.pollAsyncResult(dsRequest);

        Assertions.assertEquals("418", response.getBody().getServiceOrderStatus().getStatusType().getCode());
        Assertions.assertEquals("Unexpected async smart result response", response.getBody().getServiceOrderStatus().getStatusType().getDesc());
    }

    @Test
    void testSupportsShouldReturnTrueForSmartGetAsyncPath() {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);

        Assertions.assertTrue(asyncService.supports(createPollRequest("req-6")));
    }

    @Test
    void testSupportsShouldReturnFalseForNonAsyncPath() {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);
        DSRequest dsRequest = createSyncRequest("123");

        Assertions.assertFalse(asyncService.supports(dsRequest));
    }

    @Test
    void testSupportsShouldReturnTrueForAsyncSubmissionRequest() {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);

        Assertions.assertTrue(asyncService.supports(createSubmissionRequest("123", "SmartService", RequestMethod.PUT.name())));
    }

    @Test
    void testSupportsShouldReturnFalseForRegularSyncRequest() {
        AsyncService asyncService = new AsyncService(dsRequestService, httpCallService, smartOrderService);

        Assertions.assertFalse(asyncService.supports(createSyncRequest("123")));
    }

    @Test
    void testProcessShouldDelegateToAsyncProcessWhenRequestIsAsync() {
        AsyncService asyncService = spy(new AsyncService(dsRequestService, httpCallService, smartOrderService));
        DSRequest dsRequest = createSubmissionRequest("123", "SmartService", RequestMethod.PUT.name());
        DSResponse expected = new DSResponse();
        doReturn(expected).when(asyncService).asyncProcess(dsRequest);

        DSResponse response = asyncService.process(dsRequest);

        Assertions.assertSame(expected, response);
        verify(asyncService).asyncProcess(dsRequest);
    }

    @Test
    void testProcessShouldDelegateToPollAsyncResultWhenRequestIsSyncPoll() {
        AsyncService asyncService = spy(new AsyncService(dsRequestService, httpCallService, smartOrderService));
        DSRequest dsRequest = createPollRequest("req-7");
        DSResponse expected = new DSResponse();
        doReturn(expected).when(asyncService).pollAsyncResult(dsRequest);

        DSResponse response = asyncService.process(dsRequest);

        Assertions.assertSame(expected, response);
        verify(asyncService).pollAsyncResult(dsRequest);
    }

    private DSRequest createSubmissionRequest(String serviceOrderId, String serviceName, String serviceType) {
        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setServiceOrderID(serviceOrderId);
        serviceOrder.setServiceName(serviceName);
        serviceOrder.setServiceType(serviceType);

        DSRequest.Body body = new DSRequest.Body();
        body.setServiceOrder(serviceOrder);
        body.setAsyncronousResponse(Boolean.TRUE.toString());

        DSRequest dsRequest = new DSRequest();
        dsRequest.setBody(body);
        return dsRequest;
    }

    private DSRequest createPollRequest(String requestId) {
        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setServiceOrderID(requestId);
        serviceOrder.setServiceName("SmartService");
        serviceOrder.setServiceType(RequestMethod.GET.name());

        KeyValuesType path = new KeyValuesType();
        path.setKey("path");
        path.setValue("/async");
        serviceOrder.getParams().add(path);

        DSRequest.Body body = new DSRequest.Body();
        body.setServiceOrder(serviceOrder);
        body.setAsyncronousResponse(Boolean.FALSE.toString());

        DSRequest dsRequest = new DSRequest();
        dsRequest.setBody(body);
        return dsRequest;
    }

    private DSRequest createSyncRequest(String serviceOrderId) {
        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setServiceOrderID(serviceOrderId);
        serviceOrder.setServiceName("SmartService");
        serviceOrder.setServiceType(RequestMethod.GET.name());

        DSRequest.Body body = new DSRequest.Body();
        body.setServiceOrder(serviceOrder);
        body.setAsyncronousResponse(Boolean.FALSE.toString());

        DSRequest dsRequest = new DSRequest();
        dsRequest.setBody(body);
        return dsRequest;
    }
}
