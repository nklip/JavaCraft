package my.javacraft.elastic.api.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;
import my.javacraft.elastic.api.validation.ValueOfEnum;

@Data
@ToString
public class ContentSearchRequest {

    @ValueOfEnum(enumClass = ContentCategory.class)
    String type;

    @NotBlank
    String pattern;

    @ValueOfEnum(enumClass = ClientType.class)
    String client;

}
