package dev.nklip.javacraft.elastic.data.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import dev.nklip.javacraft.elastic.data.json.CompaniesDownloader.CompanyRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompaniesDownloaderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testCompanyRecordFieldsAreSortedAlphabetically() {
        CompanyRecord record = new CompanyRecord(
                3_000_000_000_000L,
                "Tim Cook",
                "United States",
                "Technology company",
                164000L,
                1976,
                "Cupertino, California",
                "Consumer electronics",
                "Apple Inc.",
                1,
                "Technology",
                "https://www.apple.com"
        );

        ObjectNode json = OBJECT_MAPPER.valueToTree(record);
        List<String> actualFields = new ArrayList<>();
        json.fieldNames().forEachRemaining(actualFields::add);

        List<String> expectedFields = actualFields.stream().sorted().toList();
        assertEquals(expectedFields, actualFields, "CompanyRecord fields must be in alphabetical order");
    }
}
