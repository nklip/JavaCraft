package dev.nklip.javacraft.ses.simulator.service;

import dev.nklip.javacraft.ses.simulator.db.FinanceDao;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Created by nikilipa on 7/25/16.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "/application.xml" })
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
}
