package my.javacraft.elastic.data.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.data.json.MoviesDownloader.Movie;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoviesDownloaderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void testMovieFieldsAreSortedAlphabetically() {
        Movie movie = new Movie(
                "Christopher Nolan",
                List.of("Action", "Sci-Fi"),
                "Inception",
                1,
                2010,
                "A thief who steals corporate secrets through dream-sharing technology."
        );

        ObjectNode json = OBJECT_MAPPER.valueToTree(movie);
        List<String> actualFields = new ArrayList<>();
        json.fieldNames().forEachRemaining(actualFields::add);

        List<String> expectedFields = actualFields.stream().sorted().toList();
        assertEquals(expectedFields, actualFields, "Movie fields must be in alphabetical order");
    }
}
