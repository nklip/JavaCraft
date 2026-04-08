package dev.nklip.javacraft.ses.simulator.service;

import dev.nklip.javacraft.ses.simulator.db.FinanceDao;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Created by nikilipa on 7/25/16.
 */
public class FinanceServiceTest {

    @Test
    public void testIsEnoughMoney() {
        FinanceDao financeDao = new FinanceDao();
        FinanceService financeService = new FinanceService(financeDao);

        String financeCode = FinanceDao.FINANCE_CODE_MIGRATION;

        int days1 = 40;
        int days2 = 60;
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days1));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days1));
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days2));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days2));

        int days3 = 80;
        int days4 = 20;
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days3));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days3));
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days4));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days4));

        int days5 = 50;
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days5));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days5));
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days5));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days5));

        int days6 = 30;
        int days7 = 70;
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days6));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days6));
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days7));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days7));

        int days8 = 10;
        int days9 = 85;
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days8));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days8));
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days9));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days9));

        int days10 = 5;
        Assertions.assertFalse(financeService.isEnoughMoney(financeCode, days8));
        Assertions.assertFalse(financeService.updateFinance(financeCode, days8));
        Assertions.assertTrue(financeService.isEnoughMoney(financeCode, days10));
        Assertions.assertTrue(financeService.updateFinance(financeCode, days10));
    }

    @Test
    public void testUnknownFinanceCode() {
        FinanceService financeService = new FinanceService(new FinanceDao());

        IllegalArgumentException enoughMoneyException = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> financeService.isEnoughMoney("UnknownFinanceCode", 10)
        );
        IllegalArgumentException updateFinanceException = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> financeService.updateFinance("UnknownFinanceCode", 10)
        );

        Assertions.assertEquals("Finance code UnknownFinanceCode not found", enoughMoneyException.getMessage());
        Assertions.assertEquals("Finance code UnknownFinanceCode not found", updateFinanceException.getMessage());
    }
}
