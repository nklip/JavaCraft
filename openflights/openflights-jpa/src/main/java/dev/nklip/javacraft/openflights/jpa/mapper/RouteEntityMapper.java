package dev.nklip.javacraft.openflights.jpa.mapper;

import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.jpa.entity.RouteEntity;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class RouteEntityMapper {

    public RouteEntity toEntity(Route route) {
        RouteEntity routeEntity = new RouteEntity();
        return copyToEntity(route, routeEntity);
    }

    public RouteEntity copyToEntity(Route route, RouteEntity routeEntity) {
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(routeEntity, "routeEntity must not be null");

        routeEntity.setAirlineCode(route.airlineCode());
        routeEntity.setAirlineId(route.airlineId());
        routeEntity.setSourceAirportCode(route.sourceAirportCode());
        routeEntity.setSourceAirportId(route.sourceAirportId());
        routeEntity.setDestinationAirportCode(route.destinationAirportCode());
        routeEntity.setDestinationAirportId(route.destinationAirportId());
        routeEntity.setCodeshare(route.codeshare());
        routeEntity.setStops(route.stops());
        routeEntity.setEquipmentCodes(new ArrayList<>(route.equipmentCodes()));
        routeEntity.refreshRouteKey();
        return routeEntity;
    }

    public String toRouteKey(Route route) {
        return toEntity(route).getRouteKey();
    }
}
