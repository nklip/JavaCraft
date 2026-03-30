package my.javacraft.soap2rest.rest.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsyncJobResultResponse {

    private String requestId;
    private Boolean result;
    private String errorMessage;
}
