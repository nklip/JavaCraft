package dev.nklip.javacraft.soap2rest.soap.service.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.soap2rest.soap.service.order.SmartService;
import java.util.List;
import dev.nklip.javacraft.soap2rest.rest.api.AsyncJobResultResponse;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSRequest;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSResponse;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.KeyValuesType;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrder;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrderStatus;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.StatusType;
import dev.nklip.javacraft.soap2rest.soap.service.DSRequestService;
import dev.nklip.javacraft.soap2rest.soap.service.HttpCallService;
import dev.nklip.javacraft.soap2rest.soap.service.RestAppEndpoints;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.HttpStatusCodeException;

@Service
public class AsyncService {

    private static final String SMART_SERVICE_NAME =
            SmartService.class.getSimpleName();
    private static final String ASYNC_ACCEPTED_DESCRIPTION = "Accepted for asynchronous processing";
    private static final String ASYNC_POLL_PATH = "/async";

    private final DSRequestService dsRequestService;
    private final HttpCallService httpCallService;
    private final SmartService smartOrderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AsyncService(
            DSRequestService dsRequestService,
            HttpCallService httpCallService,
            SmartService smartOrderService
    ) {
        this.dsRequestService = dsRequestService;
        this.httpCallService = httpCallService;
        this.smartOrderService = smartOrderService;
    }

    public boolean supports(DSRequest dsRequest) {
        return isAsyncRequested(dsRequest) || supportsAsyncPolling(dsRequest);
    }

    public DSResponse process(DSRequest dsRequest) {
        return isAsyncRequested(dsRequest)
                ? asyncProcess(dsRequest)
                : pollAsyncResult(dsRequest);
    }

    public DSResponse asyncProcess(DSRequest dsRequest) {
        ServiceOrder serviceOrder = dsRequest.getBody().getServiceOrder();
        if (!supportsAsyncSubmission(serviceOrder)) {
            return dsRequestService.getDSResponse(
                    dsRequest,
                    "501",
                    "Async is implemented only for SmartService PUT"
            );
        }

        try {
            String accountId = serviceOrder.getServiceOrderID();
            Metrics metrics = smartOrderService.toMetrics(serviceOrder.getParams());
            metrics.setAccountId(Long.parseLong(accountId));

            ResponseEntity<String> acceptedResponse = httpCallService.put(
                    RestAppEndpoints.smartAsync(accountId),
                    String.class,
                    metrics
            );
            if (acceptedResponse.getStatusCode().value() != 202 || acceptedResponse.getBody() == null) {
                return dsRequestService.getDSResponse(
                        dsRequest,
                        Integer.toString(acceptedResponse.getStatusCode().value()),
                        "Async smart request was not accepted"
                );
            }

            String requestId = acceptedResponse.getBody();
            if (requestId == null || requestId.isBlank()) {
                return dsRequestService.getDSResponse(
                        dsRequest,
                        "500",
                        "Async smart request id is missing"
                );
            }

            return dsRequestService.getOk(
                    dsRequest,
                    toServiceOrderStatus(requestId, "202", ASYNC_ACCEPTED_DESCRIPTION, null)
            );
        } catch (JsonProcessingException ex) {
            return dsRequestService.getDSResponse(
                    dsRequest,
                    "500",
                    "Unable to serialize async smart request"
            );
        }
    }

    public DSResponse pollAsyncResult(DSRequest dsRequest) {
        String requestId = dsRequest.getBody().getServiceOrder().getServiceOrderID();
        ResponseEntity<AsyncJobResultResponse> resultResponse;
        try {
            resultResponse = httpCallService.get(
                    RestAppEndpoints.smartAsyncResult(requestId),
                    AsyncJobResultResponse.class
            );
        } catch (HttpStatusCodeException ex) {
            resultResponse = toErrorResponse(ex);
        }

        AsyncJobResultResponse result = resultResponse.getBody();
        if (result == null) {
            return dsRequestService.getDSResponse(
                    dsRequest,
                    "500",
                    "Async smart result body is missing"
            );
        }

        int statusCode = resultResponse.getStatusCode().value();
        if (statusCode == 202) {
            return dsRequestService.getOk(
                    dsRequest,
                    toServiceOrderStatus(requestId, "202", ASYNC_ACCEPTED_DESCRIPTION, null)
            );
        }
        if (statusCode == 200) {
            return dsRequestService.getOk(
                    dsRequest,
                    toServiceOrderStatus(requestId, "200", null, toResult(result.getResult()))
            );
        }
        if (statusCode == 500) {
            return dsRequestService.getOk(
                    dsRequest,
                    toServiceOrderStatus(requestId, "500", result.getErrorMessage(), null)
            );
        }

        return dsRequestService.getDSResponse(
                dsRequest,
                Integer.toString(statusCode),
                "Unexpected async smart result response"
        );
    }

    private boolean supportsAsyncPolling(DSRequest dsRequest) {
        ServiceOrder serviceOrder = dsRequest.getBody().getServiceOrder();
        return SMART_SERVICE_NAME.equalsIgnoreCase(serviceOrder.getServiceName())
                && RequestMethod.GET.name().equalsIgnoreCase(serviceOrder.getServiceType())
                && ASYNC_POLL_PATH.equalsIgnoreCase(toPath(serviceOrder.getParams()));
    }

    private boolean isAsyncRequested(DSRequest dsRequest) {
        return Boolean.parseBoolean(dsRequest.getBody().getAsyncronousResponse());
    }

    private boolean supportsAsyncSubmission(ServiceOrder serviceOrder) {
        return SMART_SERVICE_NAME.equalsIgnoreCase(serviceOrder.getServiceName())
                && RequestMethod.PUT.name().equalsIgnoreCase(serviceOrder.getServiceType());
    }

    private String toResult(Boolean result) {
        return result == null ? null : result.toString();
    }

    private ResponseEntity<AsyncJobResultResponse> toErrorResponse(HttpStatusCodeException ex) {
        String responseBody = ex.getResponseBodyAsString();
        if (responseBody.isBlank()) {
            return ResponseEntity.status(ex.getStatusCode()).build();
        }

        try {
            return ResponseEntity.status(ex.getStatusCode())
                    .body(objectMapper.readValue(responseBody, AsyncJobResultResponse.class));
        } catch (JsonProcessingException jsonProcessingException) {
            throw new IllegalStateException("Unable to deserialize async smart result", jsonProcessingException);
        }
    }

    private String toPath(List<KeyValuesType> params) {
        return params.stream()
                .filter(param -> "path".equalsIgnoreCase(param.getKey()))
                .map(KeyValuesType::getValue)
                .map(String::trim)
                .findFirst()
                .orElse("");
    }

    private ServiceOrderStatus toServiceOrderStatus(
            String serviceOrderId,
            String code,
            String desc,
            String result
    ) {
        ServiceOrderStatus serviceOrderStatus = new ServiceOrderStatus();
        serviceOrderStatus.setServiceOrderID(serviceOrderId);

        StatusType statusType = new StatusType();
        statusType.setCode(code);
        statusType.setDesc(desc);
        statusType.setResult(result);
        serviceOrderStatus.setStatusType(statusType);

        return serviceOrderStatus;
    }
}
