package dev.nklip.javacraft.xlspaceship.engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;
import dev.nklip.javacraft.xlspaceship.engine.game.GameTurn;

// This class is used in UserController
@Data
@JsonPropertyOrder({"board", "game", "aliveShips"})
public class GameStatusOutput {

    @JsonProperty("board")
    private String board;

    @JsonProperty("game")
    private GameTurn game;

    @JsonProperty("aliveShips")
    private int aliveShips;
}
