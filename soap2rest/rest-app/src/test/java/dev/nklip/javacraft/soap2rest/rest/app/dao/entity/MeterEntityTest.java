package dev.nklip.javacraft.soap2rest.rest.app.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MeterEntityTest {

    @Test
    void testMeterHasScalarAccountIdAndReadOnlyAccountRelation() throws Exception {
        Field accountIdField = Meter.class.getDeclaredField("accountId");
        Field accountField = Meter.class.getDeclaredField("account");

        Column accountIdColumn = accountIdField.getAnnotation(Column.class);
        ManyToOne manyToOne = accountField.getAnnotation(ManyToOne.class);
        JoinColumn joinColumn = accountField.getAnnotation(JoinColumn.class);

        Assertions.assertNotNull(accountIdColumn);
        Assertions.assertEquals("account_id", accountIdColumn.name());
        Assertions.assertFalse(accountIdColumn.nullable());

        Assertions.assertNotNull(manyToOne);
        Assertions.assertEquals(FetchType.LAZY, manyToOne.fetch());
        Assertions.assertFalse(manyToOne.optional());

        Assertions.assertNotNull(joinColumn);
        Assertions.assertEquals("account_id", joinColumn.name());
        Assertions.assertFalse(joinColumn.insertable());
        Assertions.assertFalse(joinColumn.updatable());
        Assertions.assertFalse(joinColumn.nullable());
    }

    @Test
    void testMeterRelationKeepsForeignKeyNameStable() throws Exception {
        Field accountField = Meter.class.getDeclaredField("account");
        JoinColumn joinColumn = accountField.getAnnotation(JoinColumn.class);
        ForeignKey foreignKey = joinColumn.foreignKey();

        Assertions.assertEquals("fk_meter_to_account_id", foreignKey.name());
    }
}
