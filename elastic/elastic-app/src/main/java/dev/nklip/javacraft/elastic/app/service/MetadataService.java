package dev.nklip.javacraft.elastic.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;
import dev.nklip.javacraft.elastic.api.model.ContentCategoryMetadata;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class MetadataService {

    private final Set<ContentCategoryMetadata> contentCategoryMetadata;

    // metadata.json must always be present in the current architecture.
    // If it is missing, the application should fail fast.
    public MetadataService() throws IOException {
        ClassPathResource metadataResource = new ClassPathResource("metadata.json");
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream metadataStream = metadataResource.getInputStream()) {
            contentCategoryMetadata = objectMapper.readValue(metadataStream, new TypeReference<>() {});
        }
    }

    public Set<ContentCategoryMetadata> getContentCategoryMetadata() {
        return Collections.unmodifiableSet(contentCategoryMetadata);
    }

}
