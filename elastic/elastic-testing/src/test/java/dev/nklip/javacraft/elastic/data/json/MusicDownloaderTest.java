package dev.nklip.javacraft.elastic.data.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import dev.nklip.javacraft.elastic.data.json.MusicDownloader.MusicRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicDownloaderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testMusicRecordFieldsAreSortedAlphabetically() {
        MusicRecord record = new MusicRecord(
                "Coat of Arms",
                "Sabaton",
                "We will remember them.",
                "Uprising",
                2010,
                3
        );

        ObjectNode json = OBJECT_MAPPER.valueToTree(record);
        List<String> actualFields = new ArrayList<>();
        json.fieldNames().forEachRemaining(actualFields::add);

        List<String> expectedFields = actualFields.stream().sorted().toList();
        assertEquals(expectedFields, actualFields, "MusicRecord fields must be in alphabetical order");
    }
}
