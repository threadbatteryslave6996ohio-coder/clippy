package dev.clippy.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;

@SpringBootApplication
public class ClippyServerApplication {
    public static void main(String[] args) throws IOException {
        SpringApplication application = new SpringApplication(ClippyServerApplication.class);
        application.setDefaultProperties(ServerEnvs.springDefaults(ServerEnvs.load()));
        application.run(args);
    }
}
