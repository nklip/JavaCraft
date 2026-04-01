package dev.nklip.javacraft.soap2rest.soap.cucumber.conf;

import io.cucumber.spring.CucumberContextConfiguration;
import dev.nklip.javacraft.soap2rest.soap.Application;
import org.springframework.boot.test.context.SpringBootTest;

@CucumberContextConfiguration
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
public class CucumberSpringConfiguration {
}
