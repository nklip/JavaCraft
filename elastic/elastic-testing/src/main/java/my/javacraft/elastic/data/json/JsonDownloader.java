package my.javacraft.elastic.data.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

interface JsonDownloader<T> {
    ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();
    Logger log = LoggerFactory.getLogger(JsonDownloader.class);

    String datasetName();

    Path defaultOutputFile();

    List<T> downloadRecords() throws IOException;

    default void run(String[] args) throws IOException {
        export(resolveOutputFile(args));
    }

    default void export(Path outputFile) throws IOException {
        writeRecords(outputFile, downloadRecords());
    }

    default void writeRecords(Path outputFile, List<T> records) throws IOException {
        if (outputFile == null) {
            throw new IllegalArgumentException("outputFile must not be null");
        }

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String json = SHARED_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(records);
        Files.writeString(outputFile, json, StandardCharsets.UTF_8);
        log.info("Exported {} {} to '{}'", records.size(), datasetName(), outputFile.toAbsolutePath());
    }

    default Path resolveOutputFile(String[] args) {
        return Optional.ofNullable(args)
                .filter(values -> values.length > 0)
                .filter(values -> values[0] != null)
                .filter(values -> !values[0].isBlank())
                .map(values -> Path.of(values[0].trim()))
                .orElse(defaultOutputFile());
    }

}
