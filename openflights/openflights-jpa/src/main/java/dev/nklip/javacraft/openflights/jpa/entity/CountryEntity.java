package dev.nklip.javacraft.openflights.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "country",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_country_name", columnNames = "name"),
                @UniqueConstraint(name = "uk_country_iso_code", columnNames = "iso_code")
        }
)
public class CountryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "iso_code")
    private String isoCode;

    @Column(name = "dafif_code")
    private String dafifCode;
}
