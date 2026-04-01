package dev.nklip.javacraft.soap2rest.soap.service.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.KeyValuesType;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrder;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrderStatus;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.StatusType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMethod;

public interface OrderService {

    default String getServiceName() {
        return this.getClass().getSimpleName();
    }

    default ServiceOrderStatus process(ServiceOrder serviceOrder) {
        ServiceOrderStatus sos = new ServiceOrderStatus();
        StatusType statusType = new StatusType();
        sos.setStatusType(statusType);
        try {
            sos.setStatusType(statusType);

            String type = serviceOrder.getServiceType();
            switch (RequestMethod.valueOf(type)) {
                case PUT -> put(serviceOrder, statusType);
                case DELETE -> delete(serviceOrder, statusType);
                case GET -> get(serviceOrder, statusType);
                default -> {
                    statusType.setCode(Integer.toString(HttpStatus.NOT_IMPLEMENTED.value()));
                    statusType.setResult(HttpStatus.NOT_IMPLEMENTED.getReasonPhrase());
                }
            }
        } catch (Exception e) {
            statusType.setCode(Integer.toString(HttpStatus.INTERNAL_SERVER_ERROR.value()));
            statusType.setResult(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
        }
        return sos;
    }

    void put(ServiceOrder serviceOrder, StatusType statusType) throws JsonProcessingException;
    void delete(ServiceOrder serviceOrder, StatusType statusType);
    void get(ServiceOrder serviceOrder, StatusType statusType);

    default Metric toMetric(List<KeyValuesType> paramsList) {
        Metric metric = new Metric();
        for (KeyValuesType key : paramsList) {
            switch (key.getKey()) {
                case "meterId" -> metric.setMeterId(Long.parseLong(key.getValue()));
                case "reading" -> metric.setReading(new BigDecimal(key.getValue()));
                case "date" -> metric.setDate(Date.valueOf(key.getValue()));
            }
        }
        return metric;
    }

    default Metrics toMetrics(List<KeyValuesType> paramsList) throws JsonProcessingException {
        Metrics metrics = new Metrics();
        List<Metric> gasMetrics = new ArrayList<>();
        List<Metric> elecMetrics = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (KeyValuesType key : paramsList) {
            if (key.getKey().equalsIgnoreCase("gasMetric")) {
                Metric metric = mapper.readValue(key.getValue(), Metric.class);
                gasMetrics.add(metric);
            } else if (key.getKey().equalsIgnoreCase("elecMetric")) {
                Metric metric = mapper.readValue(key.getValue(), Metric.class);
                elecMetrics.add(metric);
            }
        }

        Collections.sort(gasMetrics);
        Collections.sort(elecMetrics);

        metrics.setGasReadings(gasMetrics);
        metrics.setElecReadings(elecMetrics);
        return metrics;
    }

    default String toPath(List<KeyValuesType> paramsList) {
        return paramsList
                .stream()
                .filter(keyValuesType -> keyValuesType.getKey().equalsIgnoreCase("path"))
                .map(KeyValuesType::getValue)
                .map(String::trim)
                .findFirst()
                .orElse("");
    }

    /**
     * Normalizes delete responses across rest-app contract versions.
     * Older stubs may return "true"/"false", while current rest-app returns deleted row counts.
     */
    default String toDeleteResult(ResponseEntity<String> responseEntity) {
        String body = Optional.ofNullable(responseEntity.getBody())
                .map(String::trim)
                .orElse("");
        if (Boolean.TRUE.toString().equalsIgnoreCase(body)
                || Boolean.FALSE.toString().equalsIgnoreCase(body)) {
            return Boolean.toString(Boolean.parseBoolean(body));
        }
        return Boolean.toString(responseEntity.getStatusCode().is2xxSuccessful());
    }

}
