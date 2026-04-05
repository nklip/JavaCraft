package dev.nklip.javacraft.soap2rest.rest.app.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@Entity
@Table(name = "meter")
public class Meter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manufacturer")
    private String manufacturer;

    /**
     * Raw foreign-key value stored in {@code meter.account_id}.
     * <p>
     * How it works:
     * 1) This field is the write owner for the column.
     * 2) JPA includes it in INSERT/UPDATE statements.
     * 3) Service/DAO code that only needs the account identifier can read this value directly
     *    without touching the lazy {@link #account} association.
     * 4) Database-level referential integrity is still enforced by the FK
     *    {@code fk_meter_to_account_id -> account(id)} configured in Liquibase.
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Read-only object view of the same FK column {@code meter.account_id}.
     * <p>
     * Why {@code insertable = false, updatable = false}:
     * 1) Both {@link #accountId} and this relation point to the same physical column.
     * 2) If both were writable, JPA would treat them as two write sources for one column
     *    and fail with duplicate column mapping semantics.
     * 3) Making this relation read-only keeps writes deterministic via {@link #accountId},
     *    while still allowing navigation to the linked {@link Account} when needed.
     * <p>
     * Practical effect:
     * - write path: set/read {#accountId}
     * - navigation path: read {#account}
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "account_id",
            insertable = false,
            updatable = false,
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_meter_to_account_id")
    )
    private Account account;

}
