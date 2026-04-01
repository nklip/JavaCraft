package dev.nklip.javacraft.elastic.app.service;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import dev.nklip.javacraft.elastic.api.model.ContentCategory;
import dev.nklip.javacraft.elastic.api.model.ContentCategoryMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MetadataServiceTest {

    @Test
    void testShouldLoadMetadataFromClasspath() throws IOException {
        MetadataService metadataService = new MetadataService();

        Set<ContentCategoryMetadata> metadata = metadataService.getContentCategoryMetadata();
        Assertions.assertNotNull(metadata);
        Assertions.assertEquals(5, metadata.size());

        Set<ContentCategory> contentCategories = metadata.stream()
                .map(ContentCategoryMetadata::contentCategory)
                .collect(Collectors.toSet());
        Assertions.assertTrue(contentCategories.contains(ContentCategory.BOOKS));
        Assertions.assertTrue(contentCategories.contains(ContentCategory.COMPANIES));
        Assertions.assertTrue(contentCategories.contains(ContentCategory.MOVIES));
        Assertions.assertTrue(contentCategories.contains(ContentCategory.MUSIC));
        Assertions.assertTrue(contentCategories.contains(ContentCategory.PEOPLE));
    }

    @Test
    void testChangeOnUnmodifiableCollection() throws IOException {
        MetadataService metadataService = new MetadataService();

        Set<ContentCategoryMetadata> set = metadataService.getContentCategoryMetadata();

        try {
            set.add(new ContentCategoryMetadata(ContentCategory.BOOKS, List.of("name")));
            Assertions.fail();
        } catch (UnsupportedOperationException uoe) {
            Assertions.assertNotNull(uoe);
        }
    }
}
