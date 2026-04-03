package dev.nklip.javacraft.openflights.jpa.repository;

import dev.nklip.javacraft.openflights.jpa.entity.RouteEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRepository extends JpaRepository<RouteEntity, Long> {

    List<RouteEntity> findByAirlineId(Integer airlineId);

    List<RouteEntity> findBySourceAirportId(Integer sourceAirportId);

    List<RouteEntity> findByDestinationAirportId(Integer destinationAirportId);

    Optional<RouteEntity> findByRouteKey(String routeKey);
}
