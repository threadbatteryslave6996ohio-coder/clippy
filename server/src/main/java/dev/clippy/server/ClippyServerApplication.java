package dev.clippy.server;

import dev.clippy.utils.envmanager.Env;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Path;

@SpringBootApplication
public class ClippyServerApplication {
    public static void main(String[] args) throws IOException {
        Env env = ServerEnvs.load();
        applySpringSystemProperties(env);
        configureCustomLoggerDirectory(env);
        SpringApplication application = new SpringApplication(ClippyServerApplication.class);
        application.run(args);
    }

    private static void applySpringSystemProperties(Env env) {
        System.setProperty("server.port", env.get(ServerEnvs.SERVER_PORT));
        System.setProperty("spring.datasource.url", env.get(ServerEnvs.SPRING_DATASOURCE_URL));
        System.setProperty("spring.datasource.username", env.get(ServerEnvs.SPRING_DATASOURCE_USERNAME));
        System.setProperty("spring.datasource.password", env.get(ServerEnvs.SPRING_DATASOURCE_PASSWORD));
        System.setProperty("clippy.auth.base-url", env.get(ServerEnvs.CLIPPY_AUTH_BASE_URL));
        System.setProperty("logging.file.name", env.get(ServerEnvs.LOGGING_FILE_NAME));
    }

    private static void configureCustomLoggerDirectory(Env env) {
        String loggingFileName = env.get(ServerEnvs.LOGGING_FILE_NAME);
        Path loggingPath = Path.of(loggingFileName == null ? "" : loggingFileName.trim());
        Path parentDirectory = loggingPath.getParent();
        String customLoggerDir = parentDirectory == null ? Path.of(".").toString() : parentDirectory.toString();
        System.setProperty("custom.logger.dir", customLoggerDir);
    }
}
