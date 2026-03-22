package my.javacraft.elastic.api.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ContentCategoryTest {

    @Test
    public void testValueByNameShouldResolveCaseInsensitiveValue() {
        Assertions.assertEquals(ContentCategory.BOOKS, ContentCategory.valueByName("books"));
    }

    @Test
    public void testValueByNameShouldFallbackToAllForUnknownValue() {
        Assertions.assertEquals(ContentCategory.ALL, ContentCategory.valueByName("unknown"));
    }

    @Test
    public void testValueByNameShouldFallbackToAllForNullValue() {
        Assertions.assertEquals(ContentCategory.ALL, ContentCategory.valueByName(null));
    }
}
