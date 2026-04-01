package dev.nklip.javacraft.xlspaceship.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.xlspaceship.impl.game.GameStatus;
import dev.nklip.javacraft.xlspaceship.impl.game.GameTurn;
import dev.nklip.javacraft.xlspaceship.impl.game.Grid;
import dev.nklip.javacraft.xlspaceship.impl.game.GridStatus;
import dev.nklip.javacraft.xlspaceship.impl.game.ships.Winger;
import dev.nklip.javacraft.xlspaceship.impl.model.ErrorResponse;
import dev.nklip.javacraft.xlspaceship.impl.model.FireRequest;
import dev.nklip.javacraft.xlspaceship.impl.model.SpaceshipProtocol;
import dev.nklip.javacraft.xlspaceship.impl.service.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

public class UserResourceTest {
    
    @Test
    public void testGetStatusByGameIdWhenGameExist() throws Exception {
        var validServices = mock(ValidationServices.class);
        var restServices = mock(RestServices.class);
        var shipServices = mock(XLSpaceshipServices.class);
        var gridServices = mock(GridServices.class);

        when(validServices.validateGameIdExist(anyString())).thenReturn(null);
        when(shipServices.status(anyString())).thenReturn(createGameStatus());

        UserResource userResource = new UserResource(
                validServices, restServices, shipServices, gridServices
        );

        ResponseEntity<?> actualGameStatus = userResource.getStatusByGameId("game-1-2-3");
        Assertions.assertNotNull(actualGameStatus);
        Assertions.assertInstanceOf(GameStatus.class, actualGameStatus.getBody());
        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "self" : {
                    "user_id" : "nikilipa",
                    "board" : [ "*.*.............", "*.*.............", ".*..............", "*.*.............", "*.*.............", "................", "................", "................", "................", "................", "................", "................", "................", "................", "................", "................" ]
                  },
                  "opponent" : {
                    "user_id" : "ai-500",
                    "board" : [ ]
                  },
                  "game" : {
                    "player_turn" : "nikilipa"
                  }
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(actualGameStatus.getBody())
        );
    }

    @Test
    public void testGetStatusByGameIdWhenGameDoesNotExist() throws Exception {
        String gameId = "game-1-2-3";
        var validServices = mock(ValidationServices.class);
        var restServices = mock(RestServices.class);
        var shipServices = mock(XLSpaceshipServices.class);
        var gridServices = mock(GridServices.class);

        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.setError(ValidationServices.GAME_NOT_FOUND.formatted(gameId));
        ResponseEntity<ErrorResponse> expectedErrorEntity = ResponseEntity.ok(errorResponse);

        when(validServices.validateGameIdExist(anyString())).thenReturn(expectedErrorEntity);

        UserResource userResource = new UserResource(
                validServices, restServices, shipServices, gridServices
        );

        ResponseEntity<?> actualGameStatus = userResource.getStatusByGameId("game-1-2-3");
        Assertions.assertNotNull(actualGameStatus);
        Assertions.assertInstanceOf(ErrorResponse.class, actualGameStatus.getBody());
        Assertions.assertEquals("Game ('game-1-2-3') is not found!",
                ((ErrorResponse) actualGameStatus.getBody()).getError());
        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "Game ('game-1-2-3') is not found!"
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(actualGameStatus.getBody())
        );
    }

    @Test
    public void testCreateNewGameWithWrongPort() throws Exception {
        var restServices = mock(RestServices.class);
        var shipServices = mock(XLSpaceshipServices.class);
        var gridServices = mock(GridServices.class);

        var validServices = new ValidationServices(shipServices);

        UserResource userResource = new UserResource(
                validServices, restServices, shipServices, gridServices
        );

        SpaceshipProtocol sp = new SpaceshipProtocol();
        sp.setHostname("localhost");
        sp.setPort("port");

        ResponseEntity<?> errorResponse = userResource.createNewGame(sp);

        Assertions.assertNotNull(errorResponse);
        Assertions.assertInstanceOf(ErrorResponse.class, errorResponse.getBody());
        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "Port should consist from numbers ('port')."
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(errorResponse.getBody())
        );
    }

    @Test
    public void testCreateNewGameWithGatewayTimeout() throws Exception {
        var restServices = mock(RestServices.class);
        var shipServices = mock(XLSpaceshipServices.class);
        var gridServices = mock(GridServices.class);

        var validServices = new ValidationServices(shipServices);
        when(shipServices.isGameIdExist(anyString())).thenReturn(false);
        when(shipServices.status(anyString())).thenReturn(createGameStatus());
        when(restServices.getCurrentHostname()).thenReturn("127.0.0.1");
        when(restServices.getCurrentPort()).thenReturn(8081);
        when(restServices.sendPostNewGameRequest(anyString(), anyInt(), any()))
                .thenThrow(HttpServerErrorException.create(
                        "Remote server timed out.",
                        HttpStatus.GATEWAY_TIMEOUT,
                        "Gateway timeout",
                        new HttpHeaders(),
                        "".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                        )
                );

        UserResource userResource = new UserResource(
                validServices, restServices, shipServices, gridServices
        );

        ResponseEntity<?> errorResponse = userResource.createNewGame("match-1-3-6");

        Assertions.assertNotNull(errorResponse);
        Assertions.assertInstanceOf(ErrorResponse.class, errorResponse.getBody());
        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "Remote server timed out."
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(errorResponse.getBody())
        );
    }

    @Test
    public void testCreateNewGameSuchGameAlreadyExist() throws Exception {
        var restServices = mock(RestServices.class);
        var shipServices = mock(XLSpaceshipServices.class);
        var gridServices = mock(GridServices.class);

        var validServices = new ValidationServices(shipServices);
        when(shipServices.isGameIdExist(anyString())).thenReturn(true);
        when(shipServices.status(anyString())).thenReturn(createGameStatus());

        UserResource userResource = new UserResource(
                validServices, restServices, shipServices, gridServices
        );

        ResponseEntity<?> errorResponse = userResource.createNewGame("match-1-3-6");

        Assertions.assertNotNull(errorResponse);
        Assertions.assertInstanceOf(ErrorResponse.class, errorResponse.getBody());
        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "Such game = 'match-1-3-6' already exists."
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(errorResponse.getBody())
        );
    }

    @Test
    void testFireRequestMoreThanFive() throws JsonProcessingException {
        var restServices = mock(RestServices.class);
        var shipServices = mock(XLSpaceshipServices.class);
        var gridServices = mock(GridServices.class);

        var validServices = new ValidationServices(shipServices);

        when(shipServices.isGameIdExist(anyString())).thenReturn(true);

        UserResource userResource = new UserResource(
                validServices, restServices, shipServices, gridServices
        );

        List<String> salvo6 = new ArrayList<>();
        salvo6.add("0x0");
        salvo6.add("1x1");
        salvo6.add("2x2");
        salvo6.add("3x3");
        salvo6.add("4x4");
        salvo6.add("5x5");
        FireRequest fireRequestMoreThanFive = new FireRequest();
        fireRequestMoreThanFive.setSalvo(salvo6);

        ResponseEntity<?> fireResponseMoreThanFive = userResource.fireInEnemy(
                "match-1-1",
                fireRequestMoreThanFive);

        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "More then 5 shots!"
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(fireResponseMoreThanFive.getBody())
        );
    }

    @Test
    void testFireRequestGameNotExist() throws JsonProcessingException {
        var restServices = mock(RestServices.class);
        var shipServices = mock(XLSpaceshipServices.class);
        var gridServices = mock(GridServices.class);

        var validServices = new ValidationServices(shipServices);

        when(shipServices.isGameIdExist(anyString())).thenReturn(false);

        UserResource userResource = new UserResource(
                validServices, restServices, shipServices, gridServices
        );

        List<String> salvo6 = new ArrayList<>();
        salvo6.add("0x0");
        salvo6.add("1x1");
        salvo6.add("2x2");
        salvo6.add("3x3");
        salvo6.add("4x4");
        salvo6.add("5x5");
        FireRequest fireRequestMoreThanFive = new FireRequest();
        fireRequestMoreThanFive.setSalvo(salvo6);

        ResponseEntity<?> fireResponseMoreThanFive = userResource.fireInEnemy(
                "match-1-1",
                fireRequestMoreThanFive);

        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "Game ('match-1-1') is not found!"
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(fireResponseMoreThanFive.getBody())
        );
    }

    @Test
    public void testStatusRequestGameNotExists() throws Exception {
        String gameId = "match-1-1";
        var restServices = mock(RestServices.class);
        var shipServices = mock(XLSpaceshipServices.class);
        var gridServices = mock(GridServices.class);

        var validServices = new ValidationServices(shipServices);

        when(shipServices.isGameIdExist(anyString())).thenReturn(false);

        UserResource userResource = new UserResource(
                validServices, restServices, shipServices, gridServices
        );

        ResponseEntity<?> actualErrorEntity = userResource.statusRequest(gameId);

        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "Game ('match-1-1') is not found!"
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(actualErrorEntity.getBody())
        );
    }

    @Test
    public void testStatusRequestGameExists() throws Exception {
        var restServices = mock(RestServices.class);
        var shipServices = mock(XLSpaceshipServices.class);

        var gridServices = new GridServices(new RandomServices());
        var validServices = new ValidationServices(shipServices);

        when(shipServices.isGameIdExist(anyString())).thenReturn(true);
        when(validServices.getStatusByGameId(anyString())).thenReturn(createGameStatus());

        UserResource userResource = new UserResource(
                validServices, restServices, shipServices, gridServices
        );

        ResponseEntity<?> gameStatus = userResource.statusRequest("match-1-1");

        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "grid" : "<table><tr class=\\"row\\"><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr></table>",
                  "game" : {
                    "player_turn" : "nikilipa"
                  },
                  "aliveShips" : 5
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(gameStatus.getBody())
        );
    }

    private GameStatus createGameStatus() {
        GameTurn gameTurn = new GameTurn();
        gameTurn.setPlayerTurn("nikilipa");

        GridStatus myStatus = new GridStatus();
        myStatus.setUserId("nikilipa");
        Winger winger1 = new Winger( 1);
        Grid grid = new Grid();
        grid.printSpaceship(0, 0, winger1);
        myStatus.setGrid(grid);

        GridStatus aiStatus = new GridStatus();
        aiStatus.setUserId("ai-500");

        GameStatus expectedGameStatus = new GameStatus();
        expectedGameStatus.setHost("localhost");
        expectedGameStatus.setPort(8080);
        expectedGameStatus.setAliveShips(5);
        expectedGameStatus.setGameTurn(gameTurn);
        expectedGameStatus.setSelf(myStatus);
        expectedGameStatus.setOpponent(aiStatus);
        return expectedGameStatus;
    }


}
