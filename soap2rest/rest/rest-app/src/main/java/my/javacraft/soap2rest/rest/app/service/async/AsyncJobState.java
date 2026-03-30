package my.javacraft.soap2rest.rest.app.service.async;

import my.javacraft.soap2rest.rest.api.AsyncJobResultResponse;

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
