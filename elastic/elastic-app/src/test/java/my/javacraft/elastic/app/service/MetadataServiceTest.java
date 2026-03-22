package my.javacraft.elastic.app.service;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import my.javacraft.elastic.api.model.ContentCategory;
import my.javacraft.elastic.api.model.ContentCategoryMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MetadataServiceTest {

    @Test
    void testShouldLoadMetadataFromClasspath() throws IOException {
        MetadataService metadataService = new MetadataService();

        Set<ContentCategoryMetadata> metadata = metadataService.getContentCategoryMetadata();
        Assertions.assertNotNull(metadata);
        Assertions.assertEquals(3, metadata.size());

        Set<ContentCategory> contentCategories = metadata.stream()
                .map(ContentCategoryMetadata::getContentCategory)
                .collect(Collectors.toSet());
        Assertions.assertTrue(contentCategories.contains(ContentCategory.BOOKS));
        Assertions.assertTrue(contentCategories.contains(ContentCategory.MOVIES));
        Assertions.assertTrue(contentCategories.contains(ContentCategory.MUSIC));
    }

    @Test
    void testChangeOnUnmodifiableCollection() throws IOException {
        MetadataService metadataService = new MetadataService();

        Set<ContentCategoryMetadata> set = metadataService.getContentCategoryMetadata();

        try {
            set.add(new ContentCategoryMetadata());
            Assertions.fail();
        } catch (UnsupportedOperationException uoe) {
            Assertions.assertNotNull(uoe);
        }
    }
}
