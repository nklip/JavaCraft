package dev.nklip.javacraft.shardshop.workload.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads retained workload data without exposing source fields through failure messages. */
final class DatasetResources {
    private DatasetResources() {
    }

    static List<List<String>> load(String path, String expectedHeader, int expectedRows) {
        return read(DatasetResources.class.getResourceAsStream(path), expectedHeader, expectedRows);
    }

    static List<List<String>> read(InputStream input, String expectedHeader, int expectedRows) {
        if (input == null) {
            throw new IllegalStateException("Dataset resource is missing");
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            if (!expectedHeader.equals(reader.readLine())) {
                throw new IllegalStateException("Dataset resource has an invalid format");
            }
            int columnCount = expectedHeader.split("\t", -1).length;
            List<List<String>> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> fields = List.of(line.split("\t", -1));
                if (fields.size() != columnCount || fields.stream().anyMatch(String::isBlank)
                        || rows.size() == expectedRows) {
                    throw new IllegalStateException("Dataset resource has an invalid format");
                }
                rows.add(fields);
            }
            if (rows.size() != expectedRows) {
                throw new IllegalStateException("Dataset resource has an invalid format");
            }
            return List.copyOf(rows);
        } catch (IOException failure) {
            throw new IllegalStateException("Dataset resource could not be read");
        }
    }
}
