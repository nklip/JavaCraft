package dev.nklip.javacraft.ses.simulator.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class FinanceCodeTest {

    @Test
    public void testGettersAndSetters() {
        FinanceCode financeCode = new FinanceCode();

        financeCode.setId(10L);
        financeCode.setFinanceCode("Finance-10");
        financeCode.setDays(120);

        Assertions.assertEquals(10L, financeCode.getId());
        Assertions.assertEquals("Finance-10", financeCode.getFinanceCode());
        Assertions.assertEquals(120, financeCode.getDays());
    }
}
