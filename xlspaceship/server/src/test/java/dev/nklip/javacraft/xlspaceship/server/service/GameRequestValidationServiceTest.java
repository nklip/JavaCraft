package dev.nklip.javacraft.xlspaceship.server.service;

import dev.nklip.javacraft.xlspaceship.engine.model.SalvoRequest;
import dev.nklip.javacraft.xlspaceship.engine.service.GameSessionService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class GameRequestValidationServiceTest {

    @Mock
    GameSessionService gameSessionService;

    @Test
    public void testValidateFireRequestCase01() {
        GameRequestValidationService gameRequestValidationService = new GameRequestValidationService(gameSessionService);
        List<String> salvo = new ArrayList<>();
        salvo.add("0x0");
        salvo.add("1x1");
        salvo.add("9x9");
        salvo.add("AxA");
        salvo.add("ExE");
        SalvoRequest salvoRequest = new SalvoRequest();
        salvoRequest.setSalvo(salvo);

        Assertions.assertNull(gameRequestValidationService.validateFireRequest(salvoRequest));
    }

    @Test
    public void testValidateFireRequestCase02() {
        GameRequestValidationService gameRequestValidationService = new GameRequestValidationService(gameSessionService);
        List<String> salvo = new ArrayList<>();
        salvo.add("0x0");
        salvo.add("1x1");
        salvo.add("9x9");
        salvo.add("AxA");
        salvo.add("BxB");
        salvo.add("ExE");
        SalvoRequest salvoRequest = new SalvoRequest();
        salvoRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = gameRequestValidationService.validateFireRequest(salvoRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                GameRequestValidationService.MORE_THEN_5,
                responseEntity.getBody().toString()
        );
    }

    @Test
    public void testValidateFireRequestCase03() {
        GameRequestValidationService gameRequestValidationService = new GameRequestValidationService(gameSessionService);
        List<String> salvo = new ArrayList<>();
        salvo.add("0x0");
        salvo.add("1x1");
        salvo.add("9x9");
        salvo.add("10x10");
        salvo.add("ExE");
        SalvoRequest salvoRequest = new SalvoRequest();
        salvoRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = gameRequestValidationService.validateFireRequest(salvoRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                "Wrong format. Shot = '10x10'.",
                responseEntity.getBody().toString()
        );
    }

    @Test
    public void testValidateFireRequestCase04() {
        GameRequestValidationService gameRequestValidationService = new GameRequestValidationService(gameSessionService);
        List<String> salvo = new ArrayList<>();
        salvo.add("0y0");
        SalvoRequest salvoRequest = new SalvoRequest();
        salvoRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = gameRequestValidationService.validateFireRequest(salvoRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                "Wrong format. Shot = '0y0'.",
                responseEntity.getBody().toString()
        );
    }

    @Test
    public void testValidateFireRequestCase05() {
        GameRequestValidationService gameRequestValidationService = new GameRequestValidationService(gameSessionService);
        List<String> salvo = new ArrayList<>();
        salvo.add("Gx0");
        SalvoRequest salvoRequest = new SalvoRequest();
        salvoRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = gameRequestValidationService.validateFireRequest(salvoRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                "Wrong format. Shot = 'Gx0'.",
                responseEntity.getBody().toString()
        );
    }

    @Test
    public void testValidateFireRequestCase06() {
        GameRequestValidationService gameRequestValidationService = new GameRequestValidationService(gameSessionService);
        List<String> salvo = new ArrayList<>();
        salvo.add("0xG");
        SalvoRequest salvoRequest = new SalvoRequest();
        salvoRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = gameRequestValidationService.validateFireRequest(salvoRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                "Wrong format. Shot = '0xG'.",
                responseEntity.getBody().toString()
        );
    }
}
