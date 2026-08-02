package dev.nklip.javacraft.ewrs.app.repository;

import dev.nklip.javacraft.ewrs.api.query.BudgetProjectionResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and mutates the SQL budget projection tables.
 * Architecture mapping: updated by {@code ProjectionApplier} during Projection Flow and queried by the Read Side
 * budget endpoints afterward.
 */
@Repository
@SuppressWarnings("unused")
public class BudgetProjectionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public BudgetProjectionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void resetFromReferenceData() {
        jdbcTemplate.getJdbcTemplate().update("delete from budget_projection");
        jdbcTemplate.getJdbcTemplate().update("""
                insert into budget_projection (budget_code, initial_budget, reserved_amount, remaining_budget, last_updated_at)
                select budget_code, initial_budget, 0, initial_budget, current_timestamp
                from budget_reference
                order by budget_code
                """);
    }

    public void recalculate(String budgetCode, Instant updatedAt) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("budgetCode", budgetCode)
                .addValue("updatedAt", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC));

        jdbcTemplate.update("""
                update budget_projection bp
                set reserved_amount = coalesce((
                    select sum(wrp.estimate)
                    from work_request_projection wrp
                    where wrp.budget_code = bp.budget_code
                      and wrp.status in ('ACCEPTED', 'RUNNING', 'COMPLETED')
                ), 0),
                    remaining_budget = bp.initial_budget - coalesce((
                    select sum(wrp.estimate)
                    from work_request_projection wrp
                    where wrp.budget_code = bp.budget_code
                      and wrp.status in ('ACCEPTED', 'RUNNING', 'COMPLETED')
                ), 0),
                    last_updated_at = :updatedAt
                where bp.budget_code = :budgetCode
                """, parameters);
    }

    public Optional<BudgetProjectionResponse> findByBudgetCode(String budgetCode) {
        return jdbcTemplate.query("""
                select budget_code, initial_budget, reserved_amount, remaining_budget
                from budget_projection
                where budget_code = :budgetCode
                """,
                Map.of("budgetCode", budgetCode), (resultSet, rowNumber) -> new BudgetProjectionResponse(
                        resultSet.getString("budget_code"),
                        resultSet.getInt("initial_budget"),
                        resultSet.getInt("reserved_amount"),
                        resultSet.getInt("remaining_budget")
        )).stream().findFirst();
    }

    public List<BudgetProjectionResponse> findAll() {
        return jdbcTemplate.query("""
                select budget_code, initial_budget, reserved_amount, remaining_budget
                from budget_projection
                order by budget_code asc
                """, (resultSet, rowNumber) -> new BudgetProjectionResponse(
                        resultSet.getString("budget_code"),
                        resultSet.getInt("initial_budget"),
                        resultSet.getInt("reserved_amount"),
                        resultSet.getInt("remaining_budget")
        ));
    }

    public long count() {
        Long count = jdbcTemplate.getJdbcTemplate().queryForObject(
                "select count(*) " +
                    "from budget_projection",
                Long.class
        );
        return count == null ? 0L : count;
    }

    public boolean existsBudgetCode(String budgetCode) {
        Integer count = jdbcTemplate.getJdbcTemplate().queryForObject(
                "select count(*) " +
                    "from budget_reference " +
                    "where budget_code = ?",
                Integer.class,
                budgetCode
        );
        return count != null && count > 0;
    }
}
