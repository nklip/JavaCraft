package my.javacraft.elastic.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@EnableConfigurationProperties(SearchProperties.class)
@TestPropertySource(properties = {
        "search.fuzzy.fuzziness=2",
        "search.interval.max-gaps=3",
        "search.span.slop=3"
})
class SearchPropertiesTest {

    @Autowired
    SearchProperties searchProperties;

    @Test
    void testSearchPropertiesBindFromYaml() {
        assertEquals("2", searchProperties.fuzzy().fuzziness());
        assertEquals(3,   searchProperties.interval().maxGaps());
        assertEquals(3,   searchProperties.span().slop());
    }
}
