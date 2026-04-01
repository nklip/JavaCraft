package dev.nklip.javacraft.elastic.api.model;

import java.util.List;
import java.util.Objects;

public record ContentCategoryMetadata(
        ContentCategory contentCategory,
        List<String> searchFields
) {
    public ContentCategoryMetadata {
        Objects.requireNonNull(contentCategory, "contentCategory must not be null");
        Objects.requireNonNull(searchFields, "searchFields must not be null");
        searchFields = List.copyOf(searchFields);
    }
}
