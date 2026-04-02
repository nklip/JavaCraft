package dev.nklip.javacraft.xlspaceship.engine.service;

import dev.nklip.javacraft.xlspaceship.engine.game.Board;
import dev.nklip.javacraft.xlspaceship.engine.game.BoardStatus;
import dev.nklip.javacraft.xlspaceship.engine.model.SalvoRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AiTurnServiceTest {

    @Test
    public void testAsyncFireRequestUsesUniqueShotsForWholeSalvo() {
        LocalPlayerService localPlayerService = mock(LocalPlayerService.class);
        RandomProvider randomProvider = mock(RandomProvider.class);
        RemoteGameClient remoteGameClient = mock(RemoteGameClient.class);

        when(localPlayerService.isAI()).thenReturn(true);
        when(localPlayerService.getUserId()).thenReturn("AI");
        when(randomProvider.generateUp16()).thenReturn(0);
        when(remoteGameClient.getCurrentHostname()).thenReturn("127.0.0.1");
        when(remoteGameClient.getCurrentPort()).thenReturn(8080);

        AiTurnService aiTurnService = new AiTurnService(localPlayerService, randomProvider, remoteGameClient);

        BoardStatus opponent = new BoardStatus();
        opponent.setBoard(new Board());

        aiTurnService.asyncFireRequest("AI", "match-1", 3, opponent);

        ArgumentCaptor<SalvoRequest> requestCaptor = ArgumentCaptor.forClass(SalvoRequest.class);
        verify(remoteGameClient, timeout(1000)).fireShotByAi(
                eq("127.0.0.1"),
                eq(8080),
                eq("match-1"),
                requestCaptor.capture()
        );

        SalvoRequest salvoRequest = requestCaptor.getValue();
        Assertions.assertNotNull(salvoRequest);
        Assertions.assertEquals(List.of("0x0", "0x1", "0x2"), salvoRequest.getSalvo());
        Assertions.assertEquals(3, salvoRequest.getSalvo().size());
    }

    @Test
    public void testAsyncFireRequestDoesNotWrapAroundBoardEdges() {
        LocalPlayerService localPlayerService = mock(LocalPlayerService.class);
        RandomProvider randomProvider = mock(RandomProvider.class);
        RemoteGameClient remoteGameClient = mock(RemoteGameClient.class);

        when(localPlayerService.isAI()).thenReturn(true);
        when(localPlayerService.getUserId()).thenReturn("AI");
        when(randomProvider.generateUp16()).thenReturn(3, 4);
        when(remoteGameClient.getCurrentHostname()).thenReturn("127.0.0.1");
        when(remoteGameClient.getCurrentPort()).thenReturn(8080);

        AiTurnService aiTurnService = new AiTurnService(localPlayerService, randomProvider, remoteGameClient);

        Board board = new Board();
        board.update(15, 0, Board.HIT);
        board.update(14, 0, Board.MISS);
        board.update(14, 1, Board.MISS);
        board.update(15, 1, Board.MISS);

        BoardStatus opponent = new BoardStatus();
        opponent.setBoard(board);

        aiTurnService.asyncFireRequest("AI", "match-1", 1, opponent);

        ArgumentCaptor<SalvoRequest> requestCaptor = ArgumentCaptor.forClass(SalvoRequest.class);
        verify(remoteGameClient, timeout(1000)).fireShotByAi(
                eq("127.0.0.1"),
                eq(8080),
                eq("match-1"),
                requestCaptor.capture()
        );

        SalvoRequest salvoRequest = requestCaptor.getValue();
        Assertions.assertNotNull(salvoRequest);
        Assertions.assertEquals(List.of("3x4"), salvoRequest.getSalvo());
    }
}
