package dev.clippy.auth;

import dev.clippy.utils.logger.CustomLogger;
import dev.clippy.utils.envmanager.Env;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;

@SpringBootApplication
public class ClippyAuthServerApplication {
    public static void main(String[] args) throws IOException {
        Env env = AuthServerEnvs.load();
        logLocalDatabaseIfApplicable(env);
        SpringApplication application = new SpringApplication(ClippyAuthServerApplication.class);
        application.setDefaultProperties(AuthServerEnvs.springDefaults(env));
        application.run(args);
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
