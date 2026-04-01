package dev.nklip.javacraft.soap2rest.rest.app.service.async;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;

@Data
@NoArgsConstructor
@AllArgsConstructor
class AsyncMetrics {

    private String requestId;
    private Long accountId;
    private Metrics metrics;
}
