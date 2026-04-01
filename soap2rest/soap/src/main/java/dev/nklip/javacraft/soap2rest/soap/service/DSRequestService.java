package dev.nklip.javacraft.soap2rest.soap.service;

import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.*;
import java.util.Optional;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSRequest.Body;
import org.springframework.stereotype.Service;

@Service
public class DSRequestService {

    public DSResponse getOk(DSRequest dsRequest, ServiceOrderStatus sos) {
        DSResponse dsResponse = new DSResponse();
        dsResponse.setHeader(dsRequest.getHeader());

        DSResponse.Body body = new DSResponse.Body();
        dsResponse.setBody(body);

        body.setServiceOrderStatus(sos);

        return dsResponse;
    }

    public DSResponse getDSResponse(DSRequest dsRequest, String code, String message) {
        DSResponse dsResponse = new DSResponse();
        dsResponse.setHeader(dsRequest.getHeader());

        DSResponse.Body body = new DSResponse.Body();
        dsResponse.setBody(body);

        ServiceOrderStatus sos = new ServiceOrderStatus();
        body.setServiceOrderStatus(sos);

        Optional<String> orderId = Optional.of(dsRequest)
                .map(DSRequest::getBody)
                .map(Body::getServiceOrder)
                .map(ServiceOrder::getServiceOrderID);
        orderId.ifPresent(sos::setServiceOrderID);

        StatusType statusType = new StatusType();
        sos.setStatusType(statusType);
        statusType.setCode(code);
        statusType.setDesc(message);

        return dsResponse;
    }

}
