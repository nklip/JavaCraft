package my.javacraft.elastic.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;

// Represents the incoming event for UserActivityService
@Data
@ToString
public class UserClick {

    @NotEmpty
    String userId;
    @NotBlank
    String postId;
    @NotBlank
    String searchType;
    @NotBlank
    String action;
    @NotBlank
    String searchPattern;

}
