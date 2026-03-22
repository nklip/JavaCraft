package my.javacraft.elastic.api.model;

import co.elastic.clients.elasticsearch._types.Result;
import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class VoteResponse {

    // Deterministic ID: one document per (userId, postId) → correct aggregations
    private String documentId;

    private Result result;

}
