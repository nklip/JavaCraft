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
@Table(name = "airport")
public class AirportEntity {

    @Id
    @Column(name = "airport_id")
    private Integer airportId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "city")
    private String city;

    @Column(name = "country_name")
    private String countryName;

    @Column(name = "iata_code")
    private String iataCode;

    @Column(name = "icao_code")
    private String icaoCode;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "altitude_feet")
    private Integer altitudeFeet;

    @Column(name = "timezone_hours")
    private Double timezoneHours;

    @Column(name = "daylight_saving_time")
    private String daylightSavingTime;

    @Column(name = "timezone_name")
    private String timezoneName;

    @Column(name = "type")
    private String type;

    @Column(name = "source")
    private String source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "country_name",
            referencedColumnName = "name",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_airport_country_name")
    )
    private CountryEntity country;
}
