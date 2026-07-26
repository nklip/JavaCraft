package dev.nklip.javacraft.ess.data.json;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import dev.nklip.javacraft.ess.data.json.MoviesDownloader.Movie;
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
        List<String> actualFields = new ArrayList<>(json.propertyNames());

        List<String> expectedFields = actualFields.stream().sorted().toList();
        assertEquals(expectedFields, actualFields, "Movie fields must be in alphabetical order");
    }
}
