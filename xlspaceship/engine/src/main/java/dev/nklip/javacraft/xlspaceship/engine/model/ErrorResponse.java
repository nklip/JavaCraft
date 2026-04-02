package dev.nklip.javacraft.xlspaceship.engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ErrorResponse {

    @JsonProperty("error_message")
    private String error;

    @Override
    public String toString() {
        return error;
    }

}
