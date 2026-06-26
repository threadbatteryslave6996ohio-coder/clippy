package dev.clippy.auth;

import dev.clippy.utils.logger.CustomLogger;
import dev.clippy.utils.envmanager.Env;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Path;

@SpringBootApplication
public class ClippyAuthServerApplication {
    public static void main(String[] args) throws IOException {
        Env env = AuthServerEnvs.load();
        configureCustomLoggerDirectory(env);
        logLocalDatabaseIfApplicable(env);
        SpringApplication application = new SpringApplication(ClippyAuthServerApplication.class);
        application.setDefaultProperties(AuthServerEnvs.springDefaults(env));
        application.run(args);
    }

    private static void configureCustomLoggerDirectory(Env env) {
        String loggingFileName = env.get(AuthServerEnvs.AUTH_LOGGING_FILE_NAME);
        Path loggingPath = Path.of(loggingFileName == null ? "" : loggingFileName.trim());
        Path parentDirectory = loggingPath.getParent();
        String customLoggerDir = parentDirectory == null ? Path.of(".").toString() : parentDirectory.toString();
        System.setProperty("custom.logger.dir", customLoggerDir);
    }

    private static void logLocalDatabaseIfApplicable(Env env) {
        String datasourceUrl = env.get(AuthServerEnvs.AUTH_DATASOURCE_URL);
        if (!isLocalDatabaseUrl(datasourceUrl)) {
            return;
        }

        CustomLogger logger = new CustomLogger("auth-server");
        logger.log("Using local database: " + datasourceUrl);
    }

    private static boolean isLocalDatabaseUrl(String datasourceUrl) {
        String normalized = datasourceUrl == null ? "" : datasourceUrl.trim().toLowerCase();
        return normalized.contains("localhost")
                || normalized.contains("127.0.0.1")
                || normalized.contains("0.0.0.0")
                || normalized.contains("::1")
                || normalized.contains("jdbc:postgresql://auth-postgres")
                || normalized.contains("jdbc:postgresql://postgres");
    }
}
