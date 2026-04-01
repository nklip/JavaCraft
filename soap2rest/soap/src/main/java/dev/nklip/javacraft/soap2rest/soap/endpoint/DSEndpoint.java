package dev.nklip.javacraft.soap2rest.soap.endpoint;

import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSRequest;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSResponse;
import dev.nklip.javacraft.soap2rest.soap.service.EndpointService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

@Endpoint
public class DSEndpoint {

    public static final String NAMESPACE = "http://www.nikilipa.org/SoapServiceRequest/v01";

    @Autowired
    EndpointService endpointService;

    @PayloadRoot(
            namespace = NAMESPACE,
            localPart = "DSRequest")
    @ResponsePayload
    public DSResponse getCountry(@RequestPayload DSRequest dsRequest) {
        return endpointService.executeDsRequest(dsRequest);
    }
}
