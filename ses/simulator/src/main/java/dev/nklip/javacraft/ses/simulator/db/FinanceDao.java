package dev.nklip.javacraft.ses.simulator.db;

import dev.nklip.javacraft.ses.simulator.model.FinanceCode;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Super stupid mock for a DB.
 */
@Repository
public class FinanceDao {

    public static final String FINANCE_CODE_GENERAL = "GeneralFinance2016";
    public static final String FINANCE_CODE_MIGRATION = "MigrationFinance2016";
    public static final String FINANCE_CODE_SUPPORT = "SupportFinance2016";

    // database content?
    private static final List<FinanceCode> codes = new ArrayList<>() {{
            add(new FinanceCode() {{
                setId(1L);
                setFinanceCode(FINANCE_CODE_GENERAL);
                setDays(1500);
            }});
            add(new FinanceCode() {{
                setId(2L);
                setFinanceCode(FINANCE_CODE_MIGRATION);
                setDays(500);
            }});
            add(new FinanceCode() {{
                setId(3L);
                setFinanceCode(FINANCE_CODE_SUPPORT);
                setDays(700);
            }});
        }
    };

    public FinanceCode findFinanceCodeById(long id) {
        for (FinanceCode code : codes) {
            if (code.getId() == id) {
                return code;
            }
        }
        return null;
    }

    public FinanceCode findFinanceCodeByName(String name) {
        for (FinanceCode code : codes) {
            if (code.getFinanceCode().equals(name)) {
                return code;
            }
        }
        return null;
    }

    public boolean updateFinanceCode(String name, int days) {
        for (FinanceCode code : codes) {
            if (code.getFinanceCode().equals(name)) {
                if (code.getDays() - days >= 0) {
                    code.setDays(days);
                    return true;
                }
                return false;
            }
        }
        throw new RuntimeException(String.format("Finance Code %s not found", name));
    }

}
