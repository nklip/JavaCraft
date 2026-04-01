package dev.nklip.javacraft.xlspaceship.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.xlspaceship.impl.service.UserServices;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Slf4j
@SpringBootApplication
@ComponentScan({"dev.nklip.javacraft.xlspaceship.impl", "dev.nklip.javacraft.xlspaceship.web"})
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@EnableAspectJAutoProxy
@OpenAPIDefinition(info = @Info(
        title = "Battleship game",
        version = "1.0",
        description = "Swagger UI for Battleship online game"
))
public class Application implements ApplicationRunner {

    @Autowired
    private UserServices userServices;

    public static void main(String[] args) {
        log.info("XL Spaceship Application has started.");
        SpringApplication.run(Application.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] cmdArgs = args.getSourceArgs();
        setUpUser(cmdArgs);
    }

    private void setUpUser(String[] args) {
        if (args != null && args.length == 2) {
            String user = args[0];
            String fullName = args[1];

            userServices.setUserId(user);
            userServices.setFullName(fullName);
        } else {
            if (args != null && args.length == 1) {
                log.warn("XL Spaceship instance requires two incoming parameters.");
            }
            userServices.setUpAI();
        }
        log.info("XL Spaceship Application is going to be managed by user = '{}' where 'Full Name' = '{}'.",
                userServices.getUserId(),
                userServices.getFullName()
        );
    }



}
