package dev.nklip.javacraft.soap2rest.soap.service.routing;

import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSRequest;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.DSResponse;
import dev.nklip.javacraft.soap2rest.soap.generated.ds.ws.ServiceOrder;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.soap2rest.soap.service.DSRequestService;
import dev.nklip.javacraft.soap2rest.soap.service.order.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SyncService {

    @Autowired
    private DSRequestService dsRequestService;

    @Autowired
    private List<OrderService> orderServices;

    public DSResponse process(DSRequest dsRequest) {
        ServiceOrder serviceOrder = dsRequest.getBody().getServiceOrder();
        String serviceName = serviceOrder.getServiceName();

        return orderServices
                .stream()
                .filter(s -> s.getServiceName().equalsIgnoreCase(serviceName))
                .findFirst()
                .map(ms -> ms.process(serviceOrder))
                .map(sos -> dsRequestService.getOk(dsRequest, sos))
                .orElse(dsRequestService.getDSResponse(dsRequest, "501", "Service Name doesn't registered!"));
    }
}
