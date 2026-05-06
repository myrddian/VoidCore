package io.aeyer.voidcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("io.aeyer.voidcore")
public class VoidCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoidCoreApplication.class, args);
    }
}
