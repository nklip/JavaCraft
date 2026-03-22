package my.javacraft.elastic.api.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

// Represents the incoming request for PostService#submitPost
@Data
@ToString
public class PostRequest {

    @NotBlank
    String authorUserId;
}
