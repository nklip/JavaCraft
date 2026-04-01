package dev.nklip.javacraft.elastic.api.model;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ContentCategoryMetadataTest {

    @Test
    public void testConstructorShouldCopySearchFields() {
        List<String> fields = new ArrayList<>();
        fields.add("name");
        ContentCategoryMetadata metadata = new ContentCategoryMetadata(ContentCategory.BOOKS, fields);

        fields.add("author");

        Assertions.assertEquals(List.of("name"), metadata.searchFields());
    }

    @Test
    public void testConstructorShouldRejectNullContentCategory() {
        Assertions.assertThrows(NullPointerException.class,
                () -> new ContentCategoryMetadata(null, List.of("name")));
    }

    @Test
    public void testConstructorShouldRejectNullSearchFields() {
        Assertions.assertThrows(NullPointerException.class,
                () -> new ContentCategoryMetadata(ContentCategory.BOOKS, null));
    }
}
