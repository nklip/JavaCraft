package dev.nklip.javacraft.openflights.app.repository;

import dev.nklip.javacraft.openflights.app.model.OpenFlightsDataCleanupResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OpenFlightsAdminDataRepository {

    private final JdbcTemplate jdbcTemplate;

    public OpenFlightsDataCleanupResult cleanAllData() {
        // Child tables must be cleaned first so FK constraints stay satisfied on both H2 and PostgreSQL.
        long routeEquipmentCodesDeleted = deleteFrom("route_equipment_code");
        long routesDeleted = deleteFrom("flight_route");
        long airportsDeleted = deleteFrom("airport");
        long airlinesDeleted = deleteFrom("airline");
        long planesDeleted = deleteFrom("plane");
        long countriesDeleted = deleteFrom("country");

        return new OpenFlightsDataCleanupResult(
                countriesDeleted,
                airlinesDeleted,
                airportsDeleted,
                planesDeleted,
                routesDeleted,
                routeEquipmentCodesDeleted
        );
    }

    private long deleteFrom(String tableName) {
        return jdbcTemplate.update("delete from " + tableName);
    }
}
