package dev.nklip.javacraft.soap2rest.soap.service.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrder;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.StatusType;
import dev.nklip.javacraft.soap2rest.soap.service.HttpCallService;
import dev.nklip.javacraft.soap2rest.soap.service.RestAppEndpoints;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GasService implements OrderService  {

    private final HttpCallService httpCallService;

    @Override
    public void put(ServiceOrder serviceOrder, StatusType statusType) throws JsonProcessingException {
        String accountId = serviceOrder.getServiceOrderID();

        Metric metric = toMetric(serviceOrder.getParams());

        ResponseEntity<Metric> httpEntity = httpCallService.put(RestAppEndpoints.gas(accountId), Metric.class, metric);

        statusType.setCode(Integer.toString(httpEntity.getStatusCode().value()));
        statusType.setResult(Optional
                .of(httpEntity)
                .map(HttpEntity::getBody)
                .map(Metric::toString)
                .orElse(null)
        );
    }

    @Override
    public void delete(ServiceOrder serviceOrder, StatusType statusType) {
        String accountId = serviceOrder.getServiceOrderID();

        ResponseEntity<String> httpEntity =
                httpCallService.delete(RestAppEndpoints.gas(accountId));

        statusType.setCode(Integer.toString(httpEntity.getStatusCode().value()));
        statusType.setResult(toDeleteResult(httpEntity));
    }

    @SuppressWarnings("unchecked")
    @Override
    public void get(ServiceOrder serviceOrder, StatusType statusType) {
        String accountId = serviceOrder.getServiceOrderID();

        String path = toPath(serviceOrder.getParams());

        if (path.equalsIgnoreCase("/latest")) {
            ResponseEntity<Metric> httpEntity =
                    httpCallService.get(RestAppEndpoints.gasLatest(accountId), Metric.class);

            statusType.setCode(Integer.toString(httpEntity.getStatusCode().value()));
            statusType.setResult(Optional
                    .of(httpEntity)
                    .map(HttpEntity::getBody)
                    .map(Metric::toString)
                    .orElse(null)
            );
        } else {
            ResponseEntity<Object> httpEntity =
                    httpCallService.get(RestAppEndpoints.gas(accountId), Object.class);

            statusType.setCode(Integer.toString(httpEntity.getStatusCode().value()));

            List<Metric> metricList = (List<Metric>) httpEntity.getBody();
            statusType.setResult(Optional
                    .ofNullable(metricList)
                    .map(Object::toString)
                    .orElse("")
            );
        }
    }

}
