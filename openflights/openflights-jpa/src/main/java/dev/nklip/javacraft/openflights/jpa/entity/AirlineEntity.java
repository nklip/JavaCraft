package dev.nklip.javacraft.openflights.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "airline")
public class AirlineEntity {

    @Id
    @Column(name = "airline_id")
    private Integer airlineId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "alias")
    private String alias;

    @Column(name = "iata_code")
    private String iataCode;

    @Column(name = "icao_code")
    private String icaoCode;

    @Column(name = "callsign")
    private String callsign;

    @Column(name = "country_name")
    private String countryName;

    @Column(name = "active", nullable = false)
    private boolean active;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "country_name",
            referencedColumnName = "name",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_airline_country_name")
    )
    private CountryEntity country;
}
