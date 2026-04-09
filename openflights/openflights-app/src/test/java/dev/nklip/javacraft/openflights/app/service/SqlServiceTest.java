package dev.nklip.javacraft.openflights.app.service;

import dev.nklip.javacraft.openflights.app.model.SqlQueryResult;
import dev.nklip.javacraft.openflights.app.model.SqlQueryResultType;
import dev.nklip.javacraft.openflights.app.repository.SqlRepository;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlServiceTest {

    @Test
    void testExecuteSqlDelegatesToRepositoryWithDefaultPaging() {
        SqlRepository repository = mock(SqlRepository.class);
        SqlService service = new SqlService(repository);
        SqlQueryResult expected = new SqlQueryResult(
                SqlQueryResultType.TABLE,
                List.of("id"),
                List.of(List.of("1")),
                1,
                50,
                1,
                1,
                null
        );
        when(repository.executeSql("select 1", 1, 50)).thenReturn(expected);

        SqlQueryResult actual = service.executeSql("select 1", null, null);

        Assertions.assertEquals(expected, actual);
        verify(repository).executeSql("select 1", 1, 50);
    }

    @Test
    void testExecuteSqlRejectsBlankStatements() {
        SqlRepository repository = mock(SqlRepository.class);
        SqlService service = new SqlService(repository);

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.executeSql(" ", 1, 50));

        Assertions.assertEquals("SQL query must not be blank.", exception.getMessage());
    }

    @Test
    void testExecuteSqlRejectsInvalidPageNumber() {
        SqlRepository repository = mock(SqlRepository.class);
        SqlService service = new SqlService(repository);

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.executeSql("select 1", 0, 50));

        Assertions.assertEquals("Page number must be greater than 0.", exception.getMessage());
    }

    @Test
    void testExecuteSqlRejectsInvalidPageSize() {
        SqlRepository repository = mock(SqlRepository.class);
        SqlService service = new SqlService(repository);

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.executeSql("select 1", 1, 30));

        Assertions.assertEquals("Page size must be one of: 10, 20, 50, 100.", exception.getMessage());
    }
}
