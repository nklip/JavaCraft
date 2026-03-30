package my.javacraft.soap2rest.soap.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.soap2rest.soap.generated.ds.ws.*;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EndpointService {

    private final AsyncService asyncService;
    private final DSRequestService dsRequestService;
    private final SyncService syncService;

    public DSResponse executeDsRequest(DSRequest dsRequest) {
        try {
            return asyncService.supports(dsRequest)
                    ? asyncService.process(dsRequest)
                    : syncService.process(dsRequest);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return dsRequestService.getDSResponse(
                    dsRequest,
                    "500",
                    "Internal Server Error"
            );
        }
    }
}
