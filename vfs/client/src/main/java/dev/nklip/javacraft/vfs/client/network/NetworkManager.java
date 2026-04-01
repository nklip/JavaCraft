package dev.nklip.javacraft.vfs.client.network;

import io.netty.channel.Channel;

import java.io.IOException;
import lombok.Getter;

/**
 * Wait() / notifyAll() - notes:
 * When threads try to get socket(method getSocket()) then the threads will stop until the Main thread will open socket(method openSocket()).
 * + double checked locking
 * @author Lipatov Nikita
 */
public class NetworkManager {

    private final NettyClient nettyClient;
    @Getter
    private final MessageSender messageSender;

    private volatile Channel channel;

    public NetworkManager(UserManager userManager, MessageSender messageSender) {
        this.nettyClient = new NettyClient(userManager, this, messageSender);
        this.messageSender = messageSender;
    }

    public void openSocket(String host, String port) throws IOException {
        if (channel == null) {
            synchronized (this) {
                while(channel == null) {
                    channel = nettyClient.createChannel(host, Integer.parseInt(port));

                    messageSender.setChannel(channel);

                    notifyAll();
                }
            }
        }
    }

    public Channel getChannel() {
        if (channel == null) {
            synchronized (this) {
                while(channel == null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        System.err.println("NetworkManager.getSocket().IOException.Message=" + e.getMessage());
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return channel;
    }

    public void closeSocket() {
        if (channel != null) {
            synchronized (this) {
                channel = null;
            }
        }
    }

}
