package dev.nklip.javacraft.ess.data.json;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import dev.nklip.javacraft.ess.data.json.MusicDownloader.MusicRecord;
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
        List<String> actualFields = new ArrayList<>(json.propertyNames());

        List<String> expectedFields = actualFields.stream().sorted().toList();
        assertEquals(expectedFields, actualFields, "MusicRecord fields must be in alphabetical order");
    }
}
