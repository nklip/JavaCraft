package dev.nklip.javacraft.ses.simulator.service;

import dev.nklip.javacraft.ses.simulator.model.FinanceCode;
import dev.nklip.javacraft.ses.simulator.db.FinanceDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Created by nikilipa on 7/23/16.
 */
@Service
public class FinanceService {

    private final FinanceDao dao;

    @Autowired
    public FinanceService(FinanceDao dao) {
        this.dao = dao;
    }

    public boolean isEnoughMoney(String financeCode, int sum) {
        FinanceCode code = dao.findFinanceCodeByName(financeCode);
        return code.getDays() - sum >= 0;
    }

    public boolean updateFinance(String financeCode, int sum) {
        FinanceCode code = dao.findFinanceCodeByName(financeCode);
        if (code.getDays() - sum >= 0) {
            return dao.updateFinanceCode(financeCode, code.getDays() - sum);
        }
        return false;
    }

}
