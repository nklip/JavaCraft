package dev.nklip.javacraft.modules.app;

import java.util.ServiceLoader;
import dev.nklip.javacraft.modules.util.Util;
import dev.nklip.javacraft.modules.hello.HelloService;

public class App {

    public static void main(String[] args) {
        App app = new App();
        Util.printMessage(app.resolveMessage());
    }

    String resolveMessage() {
        return loadHelloService().sayHello();
    }

    HelloService loadHelloService() {
        return ServiceLoader.load(HelloService.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No HelloService provider found"));
    }
}
