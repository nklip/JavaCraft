package dev.nklip.javacraft.openflights.jpa.mapper;

import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.jpa.entity.PlaneEntity;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class PlaneEntityMapper {

    public PlaneEntity toEntity(Plane plane) {
        Objects.requireNonNull(plane, "plane must not be null");

        PlaneEntity planeEntity = new PlaneEntity();
        planeEntity.setName(plane.name());
        planeEntity.setIataCode(plane.iataCode());
        planeEntity.setIcaoCode(plane.icaoCode());
        return planeEntity;
    }
}
