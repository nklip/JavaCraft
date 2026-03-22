package my.javacraft.elastic.app.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ElasticsearchConfigurationTest {

    @Test
    void testIsSslEnabled() {
        Assertions.assertTrue(ElasticsearchConfiguration.isSslEnabled("true"));
        Assertions.assertTrue(ElasticsearchConfiguration.isSslEnabled("TRUE"));
        Assertions.assertTrue(ElasticsearchConfiguration.isSslEnabled("  true "));
        Assertions.assertFalse(ElasticsearchConfiguration.isSslEnabled("false"));
        Assertions.assertFalse(ElasticsearchConfiguration.isSslEnabled(null));
    }

    @Test
    void testResolveSchema() {
        Assertions.assertEquals("https", ElasticsearchConfiguration.resolveSchema(true));
        Assertions.assertEquals("http", ElasticsearchConfiguration.resolveSchema(false));
    }







}
