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
        configureCustomLoggerDirectory(env);
        SpringApplication application = new SpringApplication(ClippyServerApplication.class);
        application.setDefaultProperties(ServerEnvs.springDefaults(env));
        application.run(args);
    }

    private static void configureCustomLoggerDirectory(Env env) {
        String loggingFileName = env.get(ServerEnvs.LOGGING_FILE_NAME);
        Path loggingPath = Path.of(loggingFileName == null ? "" : loggingFileName.trim());
        Path parentDirectory = loggingPath.getParent();
        String customLoggerDir = parentDirectory == null ? Path.of(".").toString() : parentDirectory.toString();
        System.setProperty("custom.logger.dir", customLoggerDir);
    }
}
