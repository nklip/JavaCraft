package my.javacraft.elastic.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;
import my.javacraft.elastic.validation.ValueOfEnum;

// Represents the incoming event for UserActivityService
@Data
@ToString
public class UserPostEvent {

    @NotEmpty
    String userId;
    @NotBlank
    String postId;
    @NotBlank
    @ValueOfEnum(enumClass = UserAction.class)
    String action;
}
