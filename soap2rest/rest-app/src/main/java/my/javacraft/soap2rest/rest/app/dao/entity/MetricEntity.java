package my.javacraft.soap2rest.rest.app.dao.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.sql.Date;
import lombok.Data;
import my.javacraft.soap2rest.rest.api.Metric;

/**
 * Two tables are used for performance.
 */
@Data
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public class MetricEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "metric_entity_seq")
    @SequenceGenerator(name = "metric_entity_seq", allocationSize = 1)
    private Long id;

    @Column(name = "meter_id")
    private Long meterId;

    @Column(name = "reading")
    private BigDecimal reading;

    @Column(name = "date")
    private Date date;

    public Metric toApiMetric() {
        Metric metric = new Metric();
        metric.setId(id);
        metric.setMeterId(meterId);
        metric.setReading(reading);
        metric.setDate(date);
        return metric;
    }
}

