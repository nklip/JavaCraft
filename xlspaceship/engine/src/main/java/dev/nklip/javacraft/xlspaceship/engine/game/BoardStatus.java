package dev.nklip.javacraft.xlspaceship.engine.game;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
@JsonPropertyOrder({"user_id", "board"})
public class BoardStatus {

    @JsonProperty("user_id")
    private String userId;

    @JsonIgnore
    private Board board;

    @JsonProperty("board")
    private List<String> rows = new ArrayList<>();

    public List<String> getRows() {
        if (board != null) {
            rows = board.toList();
        }
        return rows;
    }


}
