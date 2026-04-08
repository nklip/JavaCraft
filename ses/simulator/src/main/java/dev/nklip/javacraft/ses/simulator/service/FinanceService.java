package dev.nklip.javacraft.ses.simulator.service;

import dev.nklip.javacraft.ses.simulator.model.FinanceCode;
import dev.nklip.javacraft.ses.simulator.db.FinanceDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Business-rule layer for finance validation.
 *
 * <p>This service sits between the validator and the in-memory finance repository.
 * It answers two questions:
 * <ol>
 *     <li>does the selected finance code still have enough capacity for this task?</li>
 *     <li>if yes, what should the remaining capacity become after acceptance?</li>
 * </ol>
 *
 * <p>Keeping these rules here instead of inside {@link FinanceDao} preserves a clean split:
 * the DAO stores values, while the service decides whether a workflow transition is allowed.
 *
 * <p>Sequence:
 * <pre>{@code
 * Validator
 *     -> FinanceService.isEnoughMoney(code, estimate)
 *     -> FinanceDao.findFinanceCodeByName(code)
 *
 * Validator
 *     -> FinanceService.updateFinance(code, estimate)
 *     -> FinanceDao.updateFinanceCode(code, remainingDays)
 * }</pre>
 */
@Service
public class FinanceService {

    private final FinanceDao dao;

    @Autowired
    public FinanceService(FinanceDao dao) {
        this.dao = dao;
    }

    public boolean isEnoughMoney(String financeCode, int sum) {
        FinanceCode code = getRequiredFinanceCode(financeCode);
        return code.getDays() - sum >= 0;
    }

    public boolean updateFinance(String financeCode, int sum) {
        FinanceCode code = getRequiredFinanceCode(financeCode);
        if (code.getDays() - sum >= 0) {
            return dao.updateFinanceCode(financeCode, code.getDays() - sum);
        }
        return false;
    }

    private FinanceCode getRequiredFinanceCode(String financeCode) {
        return dao.findFinanceCodeByName(financeCode)
                .orElseThrow(() -> new IllegalArgumentException("Finance code %s not found".formatted(financeCode)));
    }

}
