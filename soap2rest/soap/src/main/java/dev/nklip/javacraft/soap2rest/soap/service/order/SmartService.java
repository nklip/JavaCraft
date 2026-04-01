package dev.nklip.javacraft.soap2rest.soap.service.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrder;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.StatusType;
import dev.nklip.javacraft.soap2rest.soap.service.HttpCallService;
import dev.nklip.javacraft.soap2rest.soap.service.RestAppEndpoints;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SmartService implements OrderService {

    private final HttpCallService httpCallService;

    @Override
    public void put(ServiceOrder serviceOrder, StatusType statusType) throws JsonProcessingException {
        String accountId = serviceOrder.getServiceOrderID();

        Metrics metrics = toMetrics(serviceOrder.getParams());
        metrics.setAccountId(Long.parseLong(accountId));

        ResponseEntity<Boolean> httpEntity = httpCallService.put(RestAppEndpoints.smart(accountId), Boolean.class, metrics);

        statusType.setCode(Integer.toString(httpEntity.getStatusCode().value()));
        statusType.setResult(Optional
                .of(httpEntity)
                .map(HttpEntity::getBody)
                .map(Object::toString)
                .orElse(null)
        );
    }

    @Override
    public void delete(ServiceOrder serviceOrder, StatusType statusType) {
        String accountId = serviceOrder.getServiceOrderID();

        ResponseEntity<String> httpEntity =
                httpCallService.delete(RestAppEndpoints.smart(accountId));

        statusType.setCode(Integer.toString(httpEntity.getStatusCode().value()));
        statusType.setResult(toDeleteResult(httpEntity));
    }

    @Override
    public void get(ServiceOrder serviceOrder, StatusType statusType) {
        String accountId = serviceOrder.getServiceOrderID();

        String path = toPath(serviceOrder.getParams());

        if (path.equalsIgnoreCase("/latest")) {
            ResponseEntity<Metrics> httpEntity =
                    httpCallService.get(RestAppEndpoints.smartLatest(accountId), Metrics.class);

            statusType.setCode(Integer.toString(httpEntity.getStatusCode().value()));
            statusType.setResult(Optional
                    .of(httpEntity)
                    .map(HttpEntity::getBody)
                    .map(Metrics::toString)
                    .orElse(null)
            );
        } else {
            ResponseEntity<Metrics> httpEntity =
                    httpCallService.get(RestAppEndpoints.smart(accountId), Metrics.class);

            statusType.setCode(Integer.toString(httpEntity.getStatusCode().value()));
            statusType.setResult(Optional
                    .of(httpEntity)
                    .map(HttpEntity::getBody)
                    .map(Metrics::toString)
                    .orElse(null)
            );
        }
    }
}
