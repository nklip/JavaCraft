package dev.nklip.javacraft.vfs.server.network;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @author Lipatov Nikita
 */
@Getter
@Component
public class NetworkConfig {
    private final String address;
    private final int port;
    private final int pool;

    @Autowired
    public NetworkConfig(
            @Value("${server.name}") String address,
            @Value("${server.port}") String port,
            @Value("${server.pool}") String pool) {
        this.address = address;
        this.port = Integer.parseInt(port);
        this.pool = Integer.parseInt(pool);
    }

}
