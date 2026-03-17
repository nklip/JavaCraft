package my.javacraft.elastic.model;

import java.util.*;
import lombok.Data;

@Data
public class ContentCategoryMetadata {

    ContentCategory contentCategory;

    List<String> searchFields;

}
