package dev.nklip.javacraft.xlspaceship.impl.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;
import dev.nklip.javacraft.xlspaceship.impl.game.GameTurn;

// This class is used in UserResource
@Data
@JsonPropertyOrder({"grid", "game", "aliveShips"})
public class GameStatusOutput {

    @JsonProperty("grid")
    private String grid;

    @JsonProperty("game")
    private GameTurn game;

    @JsonProperty("aliveShips")
    private int aliveShips;
}
