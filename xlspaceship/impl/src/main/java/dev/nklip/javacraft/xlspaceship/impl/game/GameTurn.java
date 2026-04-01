package dev.nklip.javacraft.xlspaceship.impl.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameTurn {

    @JsonProperty("player_turn")
    private String playerTurn;

    @JsonProperty("won")
    private String won;

}
