package dev.nklip.javacraft.soap2rest.rest.app.service.async;

import dev.nklip.javacraft.soap2rest.rest.api.AsyncJobResultResponse;

public record AsyncJobState(
        String requestId,
        AsyncJobProcessingStatus status,
        Boolean result,
        String errorMessage
) {

    public AsyncJobResultResponse toResponse() {
        return new AsyncJobResultResponse(requestId, result, errorMessage);
    }
}
