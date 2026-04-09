package dev.nklip.javacraft.openflights.app.repository;

import dev.nklip.javacraft.openflights.app.model.SqlQueryResult;
import dev.nklip.javacraft.openflights.app.model.SqlQueryResultType;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@Import(SqlRepository.class)
class SqlRepositoryTest {

    @Autowired
    private SqlRepository adminSqlRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void executeSqlReturnsStructuredSelectResults() {
        SqlQueryResult result = adminSqlRepository.executeSql("select 1 as \"id\", 'Papua New Guinea' as \"name\"", 1, 50);

        Assertions.assertEquals(SqlQueryResultType.TABLE, result.type());
        Assertions.assertEquals(List.of("id", "name"), result.columns());
        Assertions.assertEquals(List.of(List.of("1", "Papua New Guinea")), result.rows());
        Assertions.assertEquals(1, result.page());
        Assertions.assertEquals(50, result.pageSize());
        Assertions.assertEquals(1, result.totalRows());
        Assertions.assertEquals(1, result.totalPages());
        Assertions.assertNull(result.message());
    }

    @Test
    void executeSqlReturnsStructuredEmptySelectResults() {
        jdbcTemplate.execute("create table test_result(id integer)");

        SqlQueryResult result = adminSqlRepository.executeSql("select id from test_result", 1, 50);

        Assertions.assertEquals(SqlQueryResultType.TABLE, result.type());
        Assertions.assertEquals(List.of("ID"), result.columns());
        Assertions.assertEquals(List.of(), result.rows());
        Assertions.assertEquals(1, result.page());
        Assertions.assertEquals(50, result.pageSize());
        Assertions.assertEquals(0, result.totalRows());
        Assertions.assertEquals(1, result.totalPages());
        Assertions.assertNull(result.message());
    }

    @Test
    void executeSqlReturnsStructuredUpdateCounts() {
        jdbcTemplate.execute("create table test_update(id integer)");

        SqlQueryResult result = adminSqlRepository.executeSql("insert into test_update(id) values (1)", 1, 50);

        Assertions.assertEquals(
                new SqlQueryResult(
                        SqlQueryResultType.MESSAGE,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        "Statement executed successfully. Updated rows: 1"
                ),
                result
        );
    }

    @Test
    void executeSqlReturnsRawCellValuesWithoutHtmlFormatting() {
        SqlQueryResult result = adminSqlRepository.executeSql("select '<b>tag</b>' as \"unsafe\"", 1, 50);

        Assertions.assertEquals(SqlQueryResultType.TABLE, result.type());
        Assertions.assertEquals(List.of("unsafe"), result.columns());
        Assertions.assertEquals(List.of(List.of("<b>tag</b>")), result.rows());
    }

    @Test
    void executeSqlAppliesRequestedPageAndPageSize() {
        SqlQueryResult result = adminSqlRepository.executeSql(
                "select 1 as item_value union all select 2 union all select 3 union all select 4 union all select 5 "
                        + "union all select 6 union all select 7 union all select 8 union all select 9 union all select 10 "
                        + "union all select 11 union all select 12 union all select 13 union all select 14 union all select 15 "
                        + "union all select 16 union all select 17 union all select 18 union all select 19 union all select 20 "
                        + "union all select 21 union all select 22",
                2,
                10
        );

        Assertions.assertEquals(SqlQueryResultType.TABLE, result.type());
        Assertions.assertEquals(List.of("ITEM_VALUE"), result.columns());
        Assertions.assertEquals(
                List.of(
                        List.of("11"),
                        List.of("12"),
                        List.of("13"),
                        List.of("14"),
                        List.of("15"),
                        List.of("16"),
                        List.of("17"),
                        List.of("18"),
                        List.of("19"),
                        List.of("20")
                ),
                result.rows()
        );
        Assertions.assertEquals(2, result.page());
        Assertions.assertEquals(10, result.pageSize());
        Assertions.assertEquals(22, result.totalRows());
        Assertions.assertEquals(3, result.totalPages());
        Assertions.assertNull(result.message());
    }

    @Test
    void executeSqlAppliesRequestedPageAndPageSizeForCommonTableExpression() {
        SqlQueryResult result = adminSqlRepository.executeSql(
                "with numbers as (select 1 as item_value union all select 2 union all select 3) "
                        + "select item_value from numbers order by item_value",
                2,
                1
        );

        Assertions.assertEquals(SqlQueryResultType.TABLE, result.type());
        Assertions.assertEquals(List.of("ITEM_VALUE"), result.columns());
        Assertions.assertEquals(List.of(List.of("2")), result.rows());
        Assertions.assertEquals(2, result.page());
        Assertions.assertEquals(1, result.pageSize());
        Assertions.assertEquals(3, result.totalRows());
        Assertions.assertEquals(3, result.totalPages());
        Assertions.assertNull(result.message());
    }
}
