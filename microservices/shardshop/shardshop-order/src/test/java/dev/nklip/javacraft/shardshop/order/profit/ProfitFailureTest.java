package dev.nklip.javacraft.shardshop.order.profit;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfitFailureTest {
    @Test
    void classifiesOnlyDefinitiveDataErrorsAsBlocked() {
        assertEquals(ProfitFailure.Reason.MISSING_CATALOG_REFERENCE,
                ProfitFailure.from(new ExecutionException(new SQLException("missing product", "23503"))).reason());
        assertEquals(ProfitFailure.Reason.BALANCE_OUT_OF_RANGE,
                ProfitFailure.from(new SQLException("balance overflow", "22003")).reason());
        assertEquals(ProfitFailure.Reason.UNAVAILABLE,
                ProfitFailure.from(new SQLException("permission denied", "42501")).reason());
        assertEquals(ProfitFailure.Reason.UNAVAILABLE,
                ProfitFailure.from(new SQLException("relation missing", "42P01")).reason());
        assertEquals(ProfitFailure.Reason.UNAVAILABLE,
                ProfitFailure.from(new SQLException("connection lost", "08006")).reason());
    }
}
