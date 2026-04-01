package dev.nklip.javacraft.soap2rest.soap.service;

import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSRequest;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSResponse;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.KeyValuesType;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrder;
import dev.nklip.javacraft.soap2rest.soap.service.routing.AsyncService;
import dev.nklip.javacraft.soap2rest.soap.service.routing.SyncService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointServiceTest {

    @Mock
    private AsyncService asyncService;

    @Mock
    private DSRequestService dsRequestService;

    @Mock
    private SyncService syncService;

    @Test
    void testExecuteDsRequestShouldDelegateToAsyncService() {
        EndpointService endpointService = new EndpointService(asyncService, dsRequestService, syncService);
        DSRequest dsRequest = createRequest(true);
        DSResponse expected = new DSResponse();
        when(asyncService.supports(dsRequest)).thenReturn(true);
        when(asyncService.process(dsRequest)).thenReturn(expected);

        DSResponse response = endpointService.executeDsRequest(dsRequest);

        Assertions.assertSame(expected, response);
        verify(asyncService).process(dsRequest);
    }

    @Test
    void testExecuteDsRequestShouldDelegateToSyncService() {
        EndpointService endpointService = new EndpointService(asyncService, dsRequestService, syncService);
        DSRequest dsRequest = createRequest(false);
        DSResponse expected = new DSResponse();
        when(asyncService.supports(dsRequest)).thenReturn(false);
        when(syncService.process(dsRequest)).thenReturn(expected);

        DSResponse response = endpointService.executeDsRequest(dsRequest);

        Assertions.assertSame(expected, response);
        verify(syncService).process(dsRequest);
    }

    @Test
    void testExecuteDsRequestShouldDelegateToAsyncPollService() {
        EndpointService endpointService = new EndpointService(asyncService, dsRequestService, syncService);
        DSRequest dsRequest = createAsyncPollRequest();
        DSResponse expected = new DSResponse();
        when(asyncService.supports(dsRequest)).thenReturn(true);
        when(asyncService.process(dsRequest)).thenReturn(expected);

        DSResponse response = endpointService.executeDsRequest(dsRequest);

        Assertions.assertSame(expected, response);
        verify(asyncService).process(dsRequest);
    }

    @Test
    void testExecuteDsRequestShouldReturnInternalServerErrorResponse() {
        EndpointService endpointService = new EndpointService(asyncService, dsRequestService, syncService);
        DSRequest dsRequest = createRequest(false);
        DSResponse expected = new DSResponse();
        when(asyncService.supports(dsRequest)).thenReturn(false);
        when(syncService.process(dsRequest)).thenThrow(new IllegalStateException("boom"));
        when(dsRequestService.getDSResponse(dsRequest, "500", "Internal Server Error")).thenReturn(expected);

        DSResponse response = endpointService.executeDsRequest(dsRequest);

        Assertions.assertSame(expected, response);
        verify(dsRequestService).getDSResponse(dsRequest, "500", "Internal Server Error");
    }
    private DSRequest createRequest(boolean async) {
        DSRequest.Body body = new DSRequest.Body();
        body.setAsyncronousResponse(Boolean.toString(async));

        DSRequest dsRequest = new DSRequest();
        dsRequest.setBody(body);
        return dsRequest;
    }

    private DSRequest createAsyncPollRequest() {
        ServiceOrder serviceOrder = new ServiceOrder();
        serviceOrder.setServiceName("SmartService");
        serviceOrder.setServiceType("GET");
        serviceOrder.setServiceOrderID("req-1");

        KeyValuesType path = new KeyValuesType();
        path.setKey("path");
        path.setValue("/async");
        serviceOrder.getParams().add(path);

        DSRequest.Body body = new DSRequest.Body();
        body.setAsyncronousResponse(Boolean.FALSE.toString());
        body.setServiceOrder(serviceOrder);

        DSRequest dsRequest = new DSRequest();
        dsRequest.setBody(body);
        return dsRequest;
    }
}
