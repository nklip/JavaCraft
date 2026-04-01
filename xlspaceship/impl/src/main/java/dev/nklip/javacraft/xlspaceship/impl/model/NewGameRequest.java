package dev.nklip.javacraft.xlspaceship.impl.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class NewGameRequest {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("full_name")
    private String fullName;

    @JsonProperty("spaceship_protocol")
    private SpaceshipProtocol spaceshipProtocol;

}
