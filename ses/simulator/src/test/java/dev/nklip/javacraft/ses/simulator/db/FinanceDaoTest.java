package dev.nklip.javacraft.ses.simulator.db;

import dev.nklip.javacraft.ses.simulator.model.FinanceCode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class FinanceDaoTest {

    @Test
    public void testSupportedFinanceCodesContainAllKnownCodes() {
        Assertions.assertEquals(
                List.of(
                        FinanceDao.FINANCE_CODE_GENERAL,
                        FinanceDao.FINANCE_CODE_SUPPORT,
                        FinanceDao.FINANCE_CODE_MIGRATION
                ),
                FinanceDao.getSupportedFinanceCodes()
        );
    }

    @Test
    public void testFindFinanceCodeByIdAndName() {
        FinanceDao financeDao = new FinanceDao();

        FinanceCode generalCode = Assertions.assertDoesNotThrow(() -> financeDao.findFinanceCodeById(1L).orElseThrow());
        FinanceCode migrationCode = Assertions.assertDoesNotThrow(() -> financeDao.findFinanceCodeByName(FinanceDao.FINANCE_CODE_MIGRATION).orElseThrow());

        Assertions.assertEquals(FinanceDao.FINANCE_CODE_GENERAL, generalCode.getFinanceCode());
        Assertions.assertEquals(1500, generalCode.getDays());
        Assertions.assertEquals(2L, migrationCode.getId());
        Assertions.assertEquals(500, migrationCode.getDays());
    }

    @Test
    public void testUpdateFinanceCodeRejectsNegativeDaysAndUnknownCodes() {
        FinanceDao financeDao = new FinanceDao();

        Assertions.assertFalse(financeDao.updateFinanceCode(FinanceDao.FINANCE_CODE_GENERAL, -1));

        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> financeDao.updateFinanceCode("UnknownFinanceCode", 5)
        );

        Assertions.assertEquals("Finance code UnknownFinanceCode not found", exception.getMessage());
        Assertions.assertTrue(financeDao.updateFinanceCode(FinanceDao.FINANCE_CODE_GENERAL, 25));
        Assertions.assertEquals(
                25,
                Assertions.assertDoesNotThrow(() -> financeDao.findFinanceCodeByName(FinanceDao.FINANCE_CODE_GENERAL).orElseThrow()).getDays()
        );
    }
}
