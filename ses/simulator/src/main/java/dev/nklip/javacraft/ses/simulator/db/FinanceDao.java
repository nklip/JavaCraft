package dev.nklip.javacraft.ses.simulator.db;

import dev.nklip.javacraft.ses.simulator.model.FinanceCode;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Small in-memory repository used by the simulator instead of a real database.
 *
 * <p>The simulator only needs a place to store finance codes and their remaining capacity in days.
 * A simple map-backed repository is enough for that purpose and keeps the example focused on the
 * workflow rather than on persistence technology.
 *
 * <p>Sequence:
 * <pre>{@code
 * Validator
 *     -> FinanceService
 *     -> FinanceDao.findFinanceCodeByName(code)
 *     -> FinanceDao.updateFinanceCode(code, remainingDays)
 * }</pre>
 *
 * <p>The repository intentionally exposes simple lookup/update operations only. Budget rules such as
 * "do not allow negative remaining capacity" live in {@code FinanceService}, not here.
 */
@Repository
public class FinanceDao {

    public static final String FINANCE_CODE_GENERAL = "GeneralFinance2016";
    public static final String FINANCE_CODE_MIGRATION = "MigrationFinance2016";
    public static final String FINANCE_CODE_SUPPORT = "SupportFinance2016";

    private final Map<Long, FinanceCode> codesById = new HashMap<>();
    private final Map<String, FinanceCode> codesByName = new HashMap<>();

    public FinanceDao() {
        addCode(1L, FINANCE_CODE_GENERAL, 1500);
        addCode(2L, FINANCE_CODE_MIGRATION, 500);
        addCode(3L, FINANCE_CODE_SUPPORT, 700);
    }

    public static List<String> getSupportedFinanceCodes() {
        return List.of(FINANCE_CODE_GENERAL, FINANCE_CODE_SUPPORT, FINANCE_CODE_MIGRATION);
    }

    public Optional<FinanceCode> findFinanceCodeById(long id) {
        return Optional.ofNullable(codesById.get(id));
    }

    public Optional<FinanceCode> findFinanceCodeByName(String name) {
        return Optional.ofNullable(codesByName.get(name));
    }

    public boolean updateFinanceCode(String name, int days) {
        if (days < 0) {
            return false;
        }

        FinanceCode code = findFinanceCodeByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Finance code %s not found".formatted(name)));
        code.setDays(days);
        return true;
    }

    private void addCode(long id, String financeCodeName, int days) {
        FinanceCode code = new FinanceCode();
        code.setId(id);
        code.setFinanceCode(financeCodeName);
        code.setDays(days);

        codesById.put(id, code);
        codesByName.put(financeCodeName, code);
    }

}
