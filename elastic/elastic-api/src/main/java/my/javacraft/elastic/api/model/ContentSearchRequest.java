package my.javacraft.elastic.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import my.javacraft.elastic.api.config.ApiLimits;
import my.javacraft.elastic.api.validation.ValueOfEnum;

@Data
@ToString
public class ContentSearchRequest {

    @NotBlank
    @Size(max = ApiLimits.MAX_ENUM_INPUT_LENGTH)
    @ValueOfEnum(enumClass = ContentCategory.class)
    private String type;

    @NotBlank
    @Size(max = ApiLimits.MAX_SEARCH_PATTERN_LENGTH)
    private String pattern;

    @NotBlank
    @Size(max = ApiLimits.MAX_ENUM_INPUT_LENGTH)
    @ValueOfEnum(enumClass = ClientType.class)
    private String client;

}
