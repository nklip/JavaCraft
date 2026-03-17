package my.javacraft.elastic.model;

import co.elastic.clients.elasticsearch._types.Result;
import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class UserPostEventResponse {

    // Deterministic ID: one document per (userId, postId) → correct aggregations
    private String documentId;

    private Result result;

}
