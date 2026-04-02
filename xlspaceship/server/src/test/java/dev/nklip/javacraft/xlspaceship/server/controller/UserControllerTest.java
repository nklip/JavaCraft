package dev.nklip.javacraft.xlspaceship.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.xlspaceship.engine.game.GameStatus;
import dev.nklip.javacraft.xlspaceship.engine.game.GameTurn;
import dev.nklip.javacraft.xlspaceship.engine.game.Board;
import dev.nklip.javacraft.xlspaceship.engine.game.BoardStatus;
import dev.nklip.javacraft.xlspaceship.engine.game.ships.Winger;
import dev.nklip.javacraft.xlspaceship.engine.model.ErrorResponse;
import dev.nklip.javacraft.xlspaceship.engine.model.SalvoRequest;
import dev.nklip.javacraft.xlspaceship.engine.model.SpaceshipProtocol;
import dev.nklip.javacraft.xlspaceship.engine.service.*;
import dev.nklip.javacraft.xlspaceship.server.service.GameRequestValidationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

public class UserControllerTest {

    @Test
    public void testCreateRematchUsesPostMapping() throws NoSuchMethodException {
        RequestMapping requestMapping = UserController.class
                .getMethod("createRematch", String.class)
                .getAnnotation(RequestMapping.class);

        Assertions.assertNotNull(requestMapping);
        Assertions.assertArrayEquals(new String[]{"/game/{gameId}/rematch"}, requestMapping.value());
        Assertions.assertArrayEquals(new RequestMethod[]{RequestMethod.POST}, requestMapping.method());
    }
    
    @Test
    public void testGetStatusByGameIdWhenGameExist() throws Exception {
        var validServices = mock(GameRequestValidationService.class);
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        when(validServices.validateGameIdExist(anyString())).thenReturn(null);
        when(shipServices.status(anyString())).thenReturn(createGameStatus());

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        ResponseEntity<?> actualGameStatus = userController.getStatusByGameId("game-1-2-3");
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
        var validServices = mock(GameRequestValidationService.class);
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.setError(GameRequestValidationService.GAME_NOT_FOUND.formatted(gameId));
        ResponseEntity<ErrorResponse> expectedErrorEntity = ResponseEntity.ok(errorResponse);

        when(validServices.validateGameIdExist(anyString())).thenReturn(expectedErrorEntity);

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        ResponseEntity<?> actualGameStatus = userController.getStatusByGameId("game-1-2-3");
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
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        var validServices = new GameRequestValidationService(shipServices);

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        SpaceshipProtocol sp = new SpaceshipProtocol();
        sp.setHostname("localhost");
        sp.setPort("port");

        ResponseEntity<?> errorResponse = userController.createNewGame(sp);

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
    public void testCreateRematchWithGatewayTimeout() throws Exception {
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        var validServices = new GameRequestValidationService(shipServices);
        GameStatus finishedGameStatus = createGameStatus();
        finishedGameStatus.getGameTurn().setWon("nikilipa");
        when(shipServices.isGameIdExist(anyString())).thenReturn(true);
        when(shipServices.status(anyString())).thenReturn(finishedGameStatus);
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

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        ResponseEntity<?> errorResponse = userController.createRematch("match-1-3-6");

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
    public void testCreateRematchWhenPreviousGameIsNotFinished() throws Exception {
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        var validServices = new GameRequestValidationService(shipServices);
        when(shipServices.isGameIdExist(anyString())).thenReturn(true);
        when(shipServices.status(anyString())).thenReturn(createGameStatus());

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        ResponseEntity<?> errorResponse = userController.createRematch("match-1-3-6");

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
    public void testCreateRematchWhenGameDoesNotExist() throws Exception {
        String gameId = "match-1-3-6";
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        var validServices = new GameRequestValidationService(shipServices);
        when(shipServices.isGameIdExist(gameId)).thenReturn(false);

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        ResponseEntity<?> errorResponse = userController.createRematch(gameId);

        Assertions.assertNotNull(errorResponse);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, errorResponse.getStatusCode());
        Assertions.assertInstanceOf(ErrorResponse.class, errorResponse.getBody());
        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "Game ('match-1-3-6') is not found!"
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(errorResponse.getBody())
        );
    }

    @Test
    void testFireRequestMoreThanFive() throws JsonProcessingException {
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        var validServices = new GameRequestValidationService(shipServices);

        when(shipServices.isGameIdExist(anyString())).thenReturn(true);

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        List<String> salvo6 = new ArrayList<>();
        salvo6.add("0x0");
        salvo6.add("1x1");
        salvo6.add("2x2");
        salvo6.add("3x3");
        salvo6.add("4x4");
        salvo6.add("5x5");
        SalvoRequest salvoRequestMoreThanFive = new SalvoRequest();
        salvoRequestMoreThanFive.setSalvo(salvo6);

        ResponseEntity<?> fireResponseMoreThanFive = userController.fireInEnemy(
                "match-1-1", salvoRequestMoreThanFive);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, fireResponseMoreThanFive.getStatusCode());
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
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        var validServices = new GameRequestValidationService(shipServices);

        when(shipServices.isGameIdExist(anyString())).thenReturn(false);

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        List<String> salvo6 = new ArrayList<>();
        salvo6.add("0x0");
        salvo6.add("1x1");
        salvo6.add("2x2");
        salvo6.add("3x3");
        salvo6.add("4x4");
        salvo6.add("5x5");
        SalvoRequest salvoRequestMoreThanFive = new SalvoRequest();
        salvoRequestMoreThanFive.setSalvo(salvo6);

        ResponseEntity<?> fireResponseMoreThanFive = userController.fireInEnemy(
                "match-1-1", salvoRequestMoreThanFive);

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
    void testFireRequestRemoteServerTimeout() throws JsonProcessingException {
        String gameId = "match-1-1";
        var validServices = mock(GameRequestValidationService.class);
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        SalvoRequest salvoRequest = new SalvoRequest();
        salvoRequest.setSalvo(List.of("0x0"));

        when(validServices.validateGameIdExist(gameId)).thenReturn(null);
        when(validServices.validateFireRequest(salvoRequest)).thenReturn(null);
        when(validServices.fireInEnemy(gameId, salvoRequest))
                .thenThrow(HttpServerErrorException.create(
                        "Remote server timed out.",
                        HttpStatus.GATEWAY_TIMEOUT,
                        "Gateway timeout",
                        new HttpHeaders(),
                        "".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                ));

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        ResponseEntity<?> fireResponse = userController.fireInEnemy(gameId, salvoRequest);

        Assertions.assertNotNull(fireResponse);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, fireResponse.getStatusCode());
        Assertions.assertInstanceOf(ErrorResponse.class, fireResponse.getBody());
        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "Remote server timed out."
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(fireResponse.getBody())
        );
    }

    @Test
    void testFireRequestConnectionRefused() throws JsonProcessingException {
        String gameId = "match-1-1";
        var validServices = mock(GameRequestValidationService.class);
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        SalvoRequest salvoRequest = new SalvoRequest();
        salvoRequest.setSalvo(List.of("0x0"));

        when(validServices.validateGameIdExist(gameId)).thenReturn(null);
        when(validServices.validateFireRequest(salvoRequest)).thenReturn(null);
        when(validServices.getStatusByGameId(gameId)).thenReturn(createGameStatus());
        when(validServices.fireInEnemy(gameId, salvoRequest)).thenThrow(
                new ResourceAccessException("Connection refused", new ConnectException("Connection refused"))
        );

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        ResponseEntity<?> fireResponse = userController.fireInEnemy(gameId, salvoRequest);

        Assertions.assertNotNull(fireResponse);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, fireResponse.getStatusCode());
        Assertions.assertInstanceOf(ErrorResponse.class, fireResponse.getBody());
        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "error_message" : "Remote server not found by host('localhost') and port('8080')."
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(fireResponse.getBody())
        );
    }

    @Test
    public void testStatusRequestGameNotExists() throws Exception {
        String gameId = "match-1-1";
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);
        var gridHtmlRenderer = mock(BoardHtmlRenderer.class);

        var validServices = new GameRequestValidationService(shipServices);

        when(shipServices.isGameIdExist(anyString())).thenReturn(false);

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        ResponseEntity<?> actualErrorEntity = userController.statusRequest(gameId);

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
        var restServices = mock(RemoteGameClient.class);
        var shipServices = mock(GameSessionService.class);

        var gridHtmlRenderer = new BoardHtmlRenderer();
        var validServices = new GameRequestValidationService(shipServices);

        when(shipServices.isGameIdExist(anyString())).thenReturn(true);
        when(validServices.getStatusByGameId(anyString())).thenReturn(createGameStatus());

        UserController userController = new UserController(
                validServices, restServices, shipServices, gridHtmlRenderer
        );

        ResponseEntity<?> gameStatus = userController.statusRequest("match-1-1");

        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "board" : "<table><tr class=\\"row\\"><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"ship\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr><tr class=\\"row\\"><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/><td class=\\"empty\\"/></tr></table>",
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

        BoardStatus myStatus = new BoardStatus();
        myStatus.setUserId("nikilipa");
        Winger winger1 = new Winger( 1);
        Board board = new Board();
        board.printSpaceship(0, 0, winger1);
        myStatus.setBoard(board);

        BoardStatus aiStatus = new BoardStatus();
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
