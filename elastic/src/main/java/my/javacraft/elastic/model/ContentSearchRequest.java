package my.javacraft.elastic.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;
import my.javacraft.elastic.validation.ValueOfEnum;

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
