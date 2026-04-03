package dev.nklip.javacraft.openflights.app.service;

import dev.nklip.javacraft.openflights.app.model.OpenFlightsDataCleanupResult;
import dev.nklip.javacraft.openflights.app.repository.OpenFlightsAdminDataRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminDataServiceTest {

    @Test
    void cleanPersistedDataDelegatesToRepository() {
        OpenFlightsAdminDataRepository repository = mock(OpenFlightsAdminDataRepository.class);
        AdminDataService service = new AdminDataService(repository);
        OpenFlightsDataCleanupResult expected = new OpenFlightsDataCleanupResult(1, 2, 3, 4, 5, 6);
        when(repository.cleanAllData()).thenReturn(expected);

        OpenFlightsDataCleanupResult actual = service.cleanPersistedData();

        Assertions.assertEquals(expected, actual);
        verify(repository).cleanAllData();
    }
}
