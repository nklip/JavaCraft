package my.javacraft.soap2rest.rest.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MetricTest {

    String json1 = """
    {
        "meterId" : 200,
        "reading" : 2536.708,
        "date" : "2023-04-01"
    }
    """;

    String json2 = """
    {
        "meterId" : 200,
        "reading" : 2636.708,
        "date" : "2023-04-03"
    }
    """;

    String json3 = """
    {
        "meterId" : 200,
        "reading" : 2639.999,
        "date" : "2023-04-04"
    }
    """;

    @Test
    public void testJsonDeserialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Metric metric = mapper.readValue(json1, Metric.class);
        Assertions.assertNotNull(metric);
    }

    @Test
    public void testSortByDateByValueAscWithAscOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Metric metric1 = mapper.readValue(json1, Metric.class);
        Metric metric2 = mapper.readValue(json2, Metric.class);
        Metric metric3 = mapper.readValue(json3, Metric.class);

        List<Metric> metricList = new ArrayList<>();
        metricList.add(metric1);
        metricList.add(metric2);
        metricList.add(metric3);

        Collections.sort(metricList);
        Assertions.assertEquals("2023-04-01", metricList.get(0).getDate().toString());
        Assertions.assertEquals("2023-04-03", metricList.get(1).getDate().toString());
        Assertions.assertEquals("2636.708", metricList.get(1).getReading().toString());
        Assertions.assertEquals("2023-04-04", metricList.get(2).getDate().toString());
        Assertions.assertEquals("2639.999", metricList.get(2).getReading().toString());
    }

    @Test
    public void testSortByDateByValueAscWithDescOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Metric metric1 = mapper.readValue(json1, Metric.class);
        Metric metric2 = mapper.readValue(json2, Metric.class);
        Metric metric3 = mapper.readValue(json3, Metric.class);

        List<Metric> metricList = new ArrayList<>();
        metricList.add(metric3);
        metricList.add(metric2);
        metricList.add(metric1);

        Collections.sort(metricList);
        Assertions.assertEquals("2023-04-01", metricList.get(0).getDate().toString());
        Assertions.assertEquals("2023-04-03", metricList.get(1).getDate().toString());
        Assertions.assertEquals("2636.708", metricList.get(1).getReading().toString());
        Assertions.assertEquals("2023-04-04", metricList.get(2).getDate().toString());
        Assertions.assertEquals("2639.999", metricList.get(2).getReading().toString());
    }

}
