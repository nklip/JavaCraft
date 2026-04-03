package dev.nklip.javacraft.openflights.jpa.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "flight_route",
        uniqueConstraints = @UniqueConstraint(name = "uk_flight_route_route_key", columnNames = "route_key")
)
public class RouteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "airline_code")
    private String airlineCode;

    @Column(name = "airline_id")
    private Integer airlineId;

    @Column(name = "source_airport_code")
    private String sourceAirportCode;

    @Column(name = "source_airport_id")
    private Integer sourceAirportId;

    @Column(name = "destination_airport_code")
    private String destinationAirportCode;

    @Column(name = "destination_airport_id")
    private Integer destinationAirportId;

    @Column(name = "route_key", nullable = false)
    private String routeKey;

    @Column(name = "codeshare", nullable = false)
    private boolean codeshare;

    @Column(name = "stops")
    private Integer stops;

    @ElementCollection
    @CollectionTable(
            name = "route_equipment_code",
            joinColumns = @JoinColumn(
                    name = "route_id",
                    foreignKey = @ForeignKey(name = "fk_route_equipment_code_route_id")
            )
    )
    @OrderColumn(name = "equipment_order")
    @Column(name = "equipment_code")
    private List<String> equipmentCodes = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "airline_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_route_airline_id")
    )
    private AirlineEntity airline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "source_airport_id",
            referencedColumnName = "airport_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_route_source_airport_id")
    )
    private AirportEntity sourceAirport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "destination_airport_id",
            referencedColumnName = "airport_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_route_destination_airport_id")
    )
    private AirportEntity destinationAirport;

    public void refreshRouteKey() {
        routeKey = String.join("|",
                keyPart(airlineId),
                keyPart(airlineCode),
                keyPart(sourceAirportId),
                keyPart(sourceAirportCode),
                keyPart(destinationAirportId),
                keyPart(destinationAirportCode),
                Boolean.toString(codeshare),
                keyPart(stops));
    }

    @PrePersist
    @PreUpdate
    private void updateRouteKey() {
        refreshRouteKey();
    }

    private static String keyPart(Object value) {
        return value == null ? "" : value.toString();
    }
}
