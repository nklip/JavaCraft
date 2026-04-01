package dev.nklip.javacraft.elastic.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import dev.nklip.javacraft.elastic.api.config.ApiLimits;
import dev.nklip.javacraft.elastic.api.validation.ValueOfEnum;

// Represents the incoming event for VoteService
@Data
@ToString
public class VoteRequest {

    @NotBlank
    @Size(max = ApiLimits.MAX_USER_ID_LENGTH)
    private String userId;
    @NotBlank
    @Size(max = ApiLimits.MAX_POST_ID_LENGTH)
    private String postId;
    @NotBlank
    @Size(max = ApiLimits.MAX_ENUM_INPUT_LENGTH)
    @ValueOfEnum(enumClass = UserAction.class)
    private String action;
}
