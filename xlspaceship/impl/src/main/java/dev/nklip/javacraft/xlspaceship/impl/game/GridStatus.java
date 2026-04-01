package dev.nklip.javacraft.xlspaceship.impl.game;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
@JsonPropertyOrder({"user_id", "board"})
public class GridStatus {

    @JsonProperty("user_id")
    private String userId;

    @JsonIgnore
    private Grid grid;

    @JsonProperty("board")
    private List<String> board = new ArrayList<>();

    public List<String> getBoard() {
        if (grid != null) {
            board = grid.toList();
        }
        return board;
    }


}
