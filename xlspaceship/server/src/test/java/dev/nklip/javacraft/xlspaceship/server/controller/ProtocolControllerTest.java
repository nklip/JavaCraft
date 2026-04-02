package dev.nklip.javacraft.xlspaceship.server.controller;

import dev.nklip.javacraft.xlspaceship.engine.game.Board;
import dev.nklip.javacraft.xlspaceship.engine.model.NewGameRequest;
import dev.nklip.javacraft.xlspaceship.engine.model.NewGameResponse;
import dev.nklip.javacraft.xlspaceship.engine.model.SalvoRequest;
import dev.nklip.javacraft.xlspaceship.engine.model.SpaceshipProtocol;
import dev.nklip.javacraft.xlspaceship.engine.service.*;
import dev.nklip.javacraft.xlspaceship.server.service.GameRequestValidationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.mockito.Mockito.*;

public class ProtocolControllerTest {

    @Test
    public void testCreateNewGame() {
        LocalPlayerService localPlayerService = mock(LocalPlayerService.class);
        BoardFactory boardFactory = mock(BoardFactory.class);
        GameSetupService gameSetupService = mock(GameSetupService.class);
        RemoteGameClient remoteGameClient = mock(RemoteGameClient.class);
        AiTurnService aiTurnService = mock(AiTurnService.class);

        GameSessionService spaceshipServices = new GameSessionService(localPlayerService, boardFactory, gameSetupService, remoteGameClient, aiTurnService);
        GameRequestValidationService gameRequestValidationService = new GameRequestValidationService(spaceshipServices);

        when(localPlayerService.getUserId()).thenReturn("AI");
        when(localPlayerService.getFullName()).thenReturn("AI-1176");
        when(gameSetupService.nextUniqueGameId()).thenReturn("match-1-2");
        when(boardFactory.newUnknownBoard()).thenReturn(new Board());
        when(boardFactory.newRandomBoard()).thenReturn(new Board());
        when(gameSetupService.chooseStartingPlayer(anyString(), anyString())).thenReturn("AI");

        ProtocolController protocolResource = new ProtocolController(gameRequestValidationService, spaceshipServices);
        NewGameResponse newGameResponse = protocolResource.createNewGame(createNewGameRequest());

        Assertions.assertNotNull(newGameResponse);
        Assertions.assertEquals("AI", newGameResponse.getUserId());
        Assertions.assertEquals("AI-1176", newGameResponse.getFullName());
        Assertions.assertEquals("match-1-2", newGameResponse.getGameId());
        Assertions.assertEquals("AI", newGameResponse.getStarting());
    }

    @Test
    public void testFireFromEnemyMalformedSalvoReturnsBadRequest() {
        GameRequestValidationService gameRequestValidationService = mock(GameRequestValidationService.class);
        GameSessionService gameSessionService = mock(GameSessionService.class);

        SalvoRequest salvoRequest = new SalvoRequest();
        salvoRequest.setSalvo(List.of("10x10"));
        ResponseEntity<?> malformedResponse = new ResponseEntity<>("Wrong format. Shot = '10x10'.", HttpStatus.BAD_REQUEST);
        doReturn(malformedResponse).when(gameRequestValidationService).validateFireRequest(salvoRequest);

        ProtocolController protocolController = new ProtocolController(gameRequestValidationService, gameSessionService);

        ResponseEntity<?> responseEntity = protocolController.fireFromEnemy("match-1", salvoRequest);

        Assertions.assertNotNull(responseEntity);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        Assertions.assertEquals("Wrong format. Shot = '10x10'.", responseEntity.getBody());
        verify(gameRequestValidationService, never()).fireFromEnemy(anyString(), any());
    }

    private NewGameRequest createNewGameRequest() {
        NewGameRequest newGameRequest = new NewGameRequest();
        newGameRequest.setFullName("Nikita"); // full name of the user who initiated the request
        newGameRequest.setUserId("nikilipa"); // id of the user who initiated the request
        SpaceshipProtocol sp = new SpaceshipProtocol();
        sp.setHostname("localhost"); // server which initiated the request
        sp.setPort("8077"); // port of the server which initiated the request
        newGameRequest.setSpaceshipProtocol(sp);
        return newGameRequest;
    }
}
