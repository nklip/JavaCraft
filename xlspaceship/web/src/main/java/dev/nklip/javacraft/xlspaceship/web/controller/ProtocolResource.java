package dev.nklip.javacraft.xlspaceship.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.xlspaceship.impl.model.FireRequest;
import dev.nklip.javacraft.xlspaceship.impl.model.NewGameRequest;
import dev.nklip.javacraft.xlspaceship.impl.model.NewGameResponse;
import dev.nklip.javacraft.xlspaceship.impl.service.ValidationServices;
import dev.nklip.javacraft.xlspaceship.impl.service.XLSpaceshipServices;
import dev.nklip.javacraft.xlspaceship.impl.RestResources;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@Tag(name = "Protocol", description = "List of APIs for protocol actions")
@RequestMapping(value = RestResources.PROTOCOL_PATH)
@RequiredArgsConstructor
public class ProtocolResource {

    private final ValidationServices validationServices;
    private final XLSpaceshipServices xlSpaceshipServices;

    /*
    Content-Type : application/json

    POST http://localhost:8079/xl-spaceship/protocol/game/new
    {
        "user_id" : "nikilipa",
        "full_name" : "Nikita Lipatov",
        "spaceship_protocol" : {
            "hostname" : "127.0.0.0",
            "port" : 9001
        }
    }

    Json response:
    {
        "user_id": "AI",
        "full_name": "AI-72",
        "game_id": "match-1",
        "starting": "nikilipa"
    }

     */
    @Operation(
            summary = "Create a new game",
            description = "API to create a new game."
    )
    @RequestMapping(
            value = "/game/new",
            method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public NewGameResponse createNewGame(@RequestBody NewGameRequest newGameRequest) {
        log.info("A new game request {}", newGameRequest);

        return xlSpaceshipServices.createRemoteGame(newGameRequest);
    }

    /*
    Content-Type : application/json

    POST http://localhost:8079/xl-spaceship/protocol/game/{gameId}
    {
        "salvo" : ["0x0", "8x4", "DxA", "AxA", "7xF"]
    }

    Json response:
    {
        "salvo" : {
            "0x0" : "hit",
            "8x4" : "hit",
            "DxA" : "kill",
            "AxA" : "miss",
            "7xF" : "miss"
        }
        "game" : {
            "player_turn" : "nikilipa"
        }
    }

     */
    @Operation(
            summary = "Shoot a barrage.",
            description = "API to shoot a barrage."
    )
    @RequestMapping(
            value = "/game/{gameId}",
            method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> fireFromEnemy(
            @PathVariable("gameId") String gameId,
            @RequestBody FireRequest fireRequestByOpponent) {
        ResponseEntity<?> validResponse = validationServices.validateFireRequest(fireRequestByOpponent);
        if (validResponse != null) {
            return validResponse;
        }

        return validationServices.fireFromEnemy(gameId, fireRequestByOpponent);
    }

}
