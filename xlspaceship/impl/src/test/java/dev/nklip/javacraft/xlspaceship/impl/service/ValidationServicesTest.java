package dev.nklip.javacraft.xlspaceship.impl.service;

import dev.nklip.javacraft.xlspaceship.impl.model.FireRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.ResponseEntity;

public class ValidationServicesTest {

    @Mock
    XLSpaceshipServices xlSpaceshipServices;

    @Test
    public void testValidateFireRequestCase01() {
        ValidationServices validationServices = new ValidationServices(xlSpaceshipServices);
        List<String> salvo = new ArrayList<>();
        salvo.add("0x0");
        salvo.add("1x1");
        salvo.add("9x9");
        salvo.add("AxA");
        salvo.add("ExE");
        FireRequest fireRequest = new FireRequest();
        fireRequest.setSalvo(salvo);

        Assertions.assertNull(validationServices.validateFireRequest(fireRequest));
    }

    @Test
    public void testValidateFireRequestCase02() {
        ValidationServices validationServices = new ValidationServices(xlSpaceshipServices);
        List<String> salvo = new ArrayList<>();
        salvo.add("0x0");
        salvo.add("1x1");
        salvo.add("9x9");
        salvo.add("AxA");
        salvo.add("BxB");
        salvo.add("ExE");
        FireRequest fireRequest = new FireRequest();
        fireRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = validationServices.validateFireRequest(fireRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                ValidationServices.MORE_THEN_5,
                responseEntity.getBody().toString()
        );
    }

    @Test
    public void testValidateFireRequestCase03() {
        ValidationServices validationServices = new ValidationServices(xlSpaceshipServices);
        List<String> salvo = new ArrayList<>();
        salvo.add("0x0");
        salvo.add("1x1");
        salvo.add("9x9");
        salvo.add("10x10");
        salvo.add("ExE");
        FireRequest fireRequest = new FireRequest();
        fireRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = validationServices.validateFireRequest(fireRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                "Wrong format. Shot = '10x10'.",
                responseEntity.getBody().toString()
        );
    }

    @Test
    public void testValidateFireRequestCase04() {
        ValidationServices validationServices = new ValidationServices(xlSpaceshipServices);
        List<String> salvo = new ArrayList<>();
        salvo.add("0y0");
        FireRequest fireRequest = new FireRequest();
        fireRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = validationServices.validateFireRequest(fireRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                "Wrong format. Shot = '0y0'.",
                responseEntity.getBody().toString()
        );
    }

    @Test
    public void testValidateFireRequestCase05() {
        ValidationServices validationServices = new ValidationServices(xlSpaceshipServices);
        List<String> salvo = new ArrayList<>();
        salvo.add("Gx0");
        FireRequest fireRequest = new FireRequest();
        fireRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = validationServices.validateFireRequest(fireRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                "Wrong format. Shot = 'Gx0'.",
                responseEntity.getBody().toString()
        );
    }

    @Test
    public void testValidateFireRequestCase06() {
        ValidationServices validationServices = new ValidationServices(xlSpaceshipServices);
        List<String> salvo = new ArrayList<>();
        salvo.add("0xG");
        FireRequest fireRequest = new FireRequest();
        fireRequest.setSalvo(salvo);

        ResponseEntity<?> responseEntity = validationServices.validateFireRequest(fireRequest);
        Assertions.assertNotNull(responseEntity);
        Assertions.assertNotNull(responseEntity.getBody());
        Assertions.assertNotNull(responseEntity.getBody().toString());
        Assertions.assertEquals(
                "Wrong format. Shot = '0xG'.",
                responseEntity.getBody().toString()
        );
    }
}
