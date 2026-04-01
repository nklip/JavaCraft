package dev.nklip.javacraft.xlspaceship.impl.game;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

// This class is used in MVCController, UserResource & business logic.
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
    private GridStatus self;

    @JsonProperty("opponent")
    private GridStatus opponent;

    @JsonProperty("game")
    private GameTurn gameTurn;

}
