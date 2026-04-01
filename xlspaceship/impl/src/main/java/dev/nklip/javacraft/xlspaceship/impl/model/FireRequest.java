package dev.nklip.javacraft.xlspaceship.impl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class FireRequest {

    @JsonProperty("salvo")
    private List<String> salvo = new ArrayList<>();

}
