package dev.nklip.javacraft.modules.hello.impl;

import dev.nklip.javacraft.modules.hello.HelloService;

public class HelloServiceImpl implements HelloService {

    @SuppressWarnings("unused")
    public static void printHelloWorld() {
        System.out.println("Hello, World!");
        System.out.println("Hello, Modules!");
    }

    public String sayHello() {
        return "Hello World from Modules!";
    }
}
