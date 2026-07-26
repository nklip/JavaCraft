package dev.nklip.javacraft.soap2rest.rest.api;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MetricsTest {

    String json = """
    {
      "accountId" : 1,
      "gasReadings" : [{
        "meterId" : 100,
        "reading" : 700.502,
        "date" : 1689807600000
      } ],
      "elecReadings" : [ {
        "meterId" : 200,
        "reading" : 2536.708,
        "date" : 1689807600000
      } ]
    }
    """;

    @Test
    public void testJsonDeserialization() {
        ObjectMapper mapper = new ObjectMapper();
        Metrics metrics = mapper.readValue(json, Metrics.class);
        Assertions.assertNotNull(metrics);
    }

}
