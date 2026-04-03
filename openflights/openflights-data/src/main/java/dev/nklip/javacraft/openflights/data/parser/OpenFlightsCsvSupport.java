package dev.nklip.javacraft.openflights.data.parser;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVParserBuilder;
import com.opencsv.ICSVParser;
import com.opencsv.exceptions.CsvValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class OpenFlightsCsvSupport {

    private OpenFlightsCsvSupport() {
    }

    static List<String> parseLine(String line) throws IOException {
        Objects.requireNonNull(line, "line must not be null");

        try (CSVReader csvReader = newReader(new StringReader(line))) {
            String[] row = csvReader.readNext();
            return row == null ? List.of() : List.of(row);
        } catch (CsvValidationException e) {
            throw new IOException("Failed to parse OpenFlights line", e);
        }
    }

    static <T> List<T> parseFile(Path file, Function<List<String>, T> mapper) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, mapper);
        }
    }

    static <T> List<T> parseStream(InputStream inputStream, Function<List<String>, T> mapper) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");

        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return parse(reader, mapper);
        }
    }

    static <T> List<T> parse(Reader reader, Function<List<String>, T> mapper) throws IOException {
        Objects.requireNonNull(reader, "reader must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");

        try (CSVReader csvReader = newReader(reader)) {
            List<T> parsedRows = new ArrayList<>();
            String[] row;
            while ((row = csvReader.readNext()) != null) {
                parsedRows.add(mapper.apply(List.of(row)));
            }
            return parsedRows;
        } catch (CsvValidationException e) {
            throw new IOException("Failed to parse OpenFlights CSV content", e);
        }
    }

    private static CSVReader newReader(Reader reader) {
        return new CSVReaderBuilder(reader)
                .withCSVParser(new CSVParserBuilder()
                        .withEscapeChar(ICSVParser.NULL_CHARACTER)
                        .build())
                .build();
    }
}
