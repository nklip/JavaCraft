package dev.nklip.javacraft.ewrs.app.service;

import dev.nklip.javacraft.ewrs.app.exception.UnknownBudgetCodeException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Evaluates budget existence and remaining capacity for write-side approval decisions.
 * Architecture mapping: invoked by {@code WorkRequestCommandService} during the Write Side before an approval either
 * appends {@code AcceptedEvent} or persists a policy-driven {@code RejectedEvent}.
 */
@Service
public class BudgetPolicyService {

    private final JdbcTemplate jdbcTemplate;

    public BudgetPolicyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void assertBudgetCodeExists(String budgetCode) {
        if (!budgetCodeExists(budgetCode)) {
            throw new UnknownBudgetCodeException("Unknown budget code: " + budgetCode);
        }
    }

    public boolean canReserve(String budgetCode, int estimate) {
        int remainingBudget = getRemainingBudget(budgetCode);
        return remainingBudget >= estimate;
    }

    public int getRemainingBudget(String budgetCode) {
        int initialBudget = getInitialBudget(budgetCode);
        int reservedBudget = getReservedBudget(budgetCode);
        return initialBudget - reservedBudget;
    }

    private boolean budgetCodeExists(String budgetCode) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) " +
                    "from budget_reference " +
                    "where budget_code = ?",
                Integer.class,
                budgetCode
        );
        return count != null && count > 0;
    }

    private int getInitialBudget(String budgetCode) {
        Integer initialBudget = jdbcTemplate.queryForObject(
                "select initial_budget " +
                    "from budget_reference " +
                    "where budget_code = ?",
                Integer.class,
                budgetCode
        );
        if (initialBudget == null) {
            throw new UnknownBudgetCodeException("Unknown budget code: " + budgetCode);
        }
        return initialBudget;
    }

    private int getReservedBudget(String budgetCode) {
        Integer reservedBudget = jdbcTemplate.queryForObject("""
                select coalesce(sum(cast(latest.payload ->> 'estimate' as integer)), 0)
                from (
                    select distinct on (task_id) task_id, status, payload
                    from event_store
                    where payload ->> 'budgetCode' = ?
                    order by task_id, stream_version desc
                ) latest
                where latest.status in ('ACCEPTED', 'RUNNING', 'COMPLETED')
                """, Integer.class, budgetCode);
        return reservedBudget == null ? 0 : reservedBudget;
    }
}
