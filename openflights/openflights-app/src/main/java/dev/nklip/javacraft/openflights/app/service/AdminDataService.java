package dev.nklip.javacraft.openflights.app.service;

import dev.nklip.javacraft.openflights.app.model.OpenFlightsDataCleanupResult;
import dev.nklip.javacraft.openflights.app.repository.OpenFlightsAdminDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminDataService {

    private final OpenFlightsAdminDataRepository adminDataRepository;

    public OpenFlightsDataCleanupResult cleanPersistedData() {
        return adminDataRepository.cleanAllData();
    }
}
