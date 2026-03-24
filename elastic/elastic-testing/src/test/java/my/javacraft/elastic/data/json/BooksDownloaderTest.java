package my.javacraft.elastic.data.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.data.json.BooksDownloader.BookRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BooksDownloaderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testBookRecordFieldsAreSortedAlphabetically() {
        BookRecord record = new BookRecord(
                "Jane Austen",
                List.of("Romance", "Classic"),
                "Pride and Prejudice",
                1,
                1813,
                "A novel about manners and matrimony."
        );

        ObjectNode json = OBJECT_MAPPER.valueToTree(record);
        List<String> actualFields = new ArrayList<>();
        json.fieldNames().forEachRemaining(actualFields::add);

        List<String> expectedFields = actualFields.stream().sorted().toList();
        assertEquals(expectedFields, actualFields, "BookRecord fields must be in alphabetical order");
    }
}
