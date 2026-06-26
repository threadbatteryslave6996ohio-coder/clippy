package dev.clippy.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;

@SpringBootApplication
public class ClippyAuthServerApplication {
    public static void main(String[] args) throws IOException {
        SpringApplication application = new SpringApplication(ClippyAuthServerApplication.class);
        application.setDefaultProperties(AuthServerEnvs.springDefaults(AuthServerEnvs.load()));
        application.run(args);
    }
}
