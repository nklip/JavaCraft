package my.javacraft.soap2rest.rest.api;

import java.util.List;
import lombok.Data;

@Data
public class Metrics {

    private Long accountId;

    private List<Metric> gasReadings;

    private List<Metric> elecReadings;
}
