package dev.nklip.javacraft.translation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "my.javacraft.translation"
})
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class, args);
    }

}
