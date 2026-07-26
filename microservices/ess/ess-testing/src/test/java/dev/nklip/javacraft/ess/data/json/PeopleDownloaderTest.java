package dev.nklip.javacraft.ess.data.json;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import dev.nklip.javacraft.ess.data.json.PeopleDownloader.PersonRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeopleDownloaderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testPersonRecordFieldsAreSortedAlphabetically() {
        PersonRecord record = new PersonRecord(
                56,
                "1452-04-15",
                "1519-05-02",
                "Leonardo",
                1,
                "Italian Renaissance polymath.",
                "da Vinci"
        );

        ObjectNode json = OBJECT_MAPPER.valueToTree(record);
        List<String> actualFields = new ArrayList<>(json.propertyNames());

        List<String> expectedFields = actualFields.stream().sorted().toList();
        assertEquals(expectedFields, actualFields, "PersonRecord fields must be in alphabetical order");
    }
}
