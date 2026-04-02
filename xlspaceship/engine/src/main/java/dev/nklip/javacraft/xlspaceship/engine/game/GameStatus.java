package dev.nklip.javacraft.xlspaceship.engine.game;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

// This class is used in MVCController, UserController & business logic.
@Data
@JsonPropertyOrder({"self", "opponent", "game"})
public class GameStatus {

    @JsonIgnore
    private String host;

    @JsonIgnore
    private int port;

    @JsonIgnore
    private int aliveShips;

    @JsonProperty("self")
    private BoardStatus self;

    @JsonProperty("opponent")
    private BoardStatus opponent;

    @JsonProperty("game")
    private GameTurn gameTurn;

}
