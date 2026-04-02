package dev.nklip.javacraft.xlspaceship.engine.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class SpaceshipProtocol {

    @NotBlank(message = "Hostname is mandatory")
    private String hostname;

    @NotBlank(message = "Port is mandatory")
    @Size(min=4, max=4)
    private String port;

}
