package dev.nklip.javacraft.xlspaceship.engine.service;

import java.net.InetAddress;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.xlspaceship.engine.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class RemoteGameClient {

    private static final String NEW_GAME_REQUEST = "http://%s:%s/xl-spaceship/protocol/game/new";
    private static final String FIRE_REQUEST = "http://%s:%s/xl-spaceship/protocol/game/%s";
    private static final String FIRE_REQUEST_AI = "http://%s:%s/xl-spaceship/user/game/%s/fire";
    private final Environment environment;
    private final LocalPlayerService localPlayerService;
    private final RestTemplate restTemplate;

    @Autowired
    public RemoteGameClient(Environment environment, LocalPlayerService localPlayerService) {
        this.environment = environment;
        this.localPlayerService = localPlayerService;
        this.restTemplate = new RestTemplate();
    }

    public int getCurrentPort() {
        return Integer.parseInt(Objects.requireNonNull(environment.getProperty("server.port")));
    }

    public String getCurrentHostname() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.error(e.getMessage());
            return "localhost";
        }
    }

    public NewGameResponse sendPostNewGameRequest(String remoteHost, int remotePort, SpaceshipProtocol spaceshipProtocol) {
        String url = String.format(NEW_GAME_REQUEST, remoteHost, remotePort);

        NewGameRequest newGameRequest = new NewGameRequest();
        newGameRequest.setUserId(localPlayerService.getUserId());
        newGameRequest.setFullName(localPlayerService.getFullName());
        newGameRequest.setSpaceshipProtocol(spaceshipProtocol);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<NewGameRequest> httpEntity = new HttpEntity<>(newGameRequest, headers);
        return restTemplate.postForObject(url, httpEntity, NewGameResponse.class);
    }

    public SalvoResponse fireShot(String remoteHost, int remotePort, String gameId, SalvoRequest salvoRequest) {
        String url = String.format(FIRE_REQUEST, remoteHost, remotePort, gameId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SalvoRequest> httpEntity = new HttpEntity<>(salvoRequest, headers);
        return restTemplate.postForObject(url, httpEntity, SalvoResponse.class);
    }

    public SalvoResponse fireShotByAi(String localHost, int localPort, String gameId, SalvoRequest salvoRequest) {
        String url = String.format(FIRE_REQUEST_AI, localHost, localPort, gameId);

        HttpEntity<SalvoRequest> entity = new HttpEntity<>(salvoRequest);
        ResponseEntity<SalvoResponse> response = restTemplate.exchange(url, HttpMethod.PUT, entity, SalvoResponse.class);
        return response.getBody();
    }

}
