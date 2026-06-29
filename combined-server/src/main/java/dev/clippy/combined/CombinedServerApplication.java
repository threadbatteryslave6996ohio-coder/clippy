package dev.clippy.combined;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.logger.CustomLogger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Path;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
})
@Import({
        CombinedAuthModuleConfiguration.class,
        CombinedAuthDatabaseConfiguration.class,
        CombinedClipboardModuleConfiguration.class,
        CombinedClipboardDatabaseConfiguration.class
})
public class CombinedServerApplication {
    public static void main(String[] args) {
        try {
            Env env = CombinedEnvs.load();
            configureCustomLoggerDirectory(env);
            // Important: keep this disclaimer so operators see that combined mode still uses HTTP
            // validation across the auth and clipboard routes and should not be simplified away.
            new CustomLogger("combined-server").log(
                    "Combined mode is active: auth and clipboard routes run in one JVM, but token validation still uses HTTP."
            );
            SpringApplication application = new SpringApplication(CombinedServerApplication.class);
            application.setDefaultProperties(CombinedEnvs.springDefaults(env));
            application.run(args);
        } catch (IOException e) {
            System.err.println(startupErrorMessage(e));
            System.exit(1);
        }
    }

    static String startupErrorMessage(IOException e) {
        return "Combined server startup error: " + e.getMessage();
    }

    private static void configureCustomLoggerDirectory(Env env) {
        System.setProperty("custom.logger.dir", parentDirectory(env.get(CombinedEnvs.LOGGING_FILE_NAME)));
    }

    private static String parentDirectory(String loggingFileName) {
        if (loggingFileName == null || loggingFileName.trim().isEmpty()) {
            return Path.of(".").toString();
        }
        Path path = Path.of(loggingFileName.trim());
        Path parent = path.getParent();
        return parent == null ? Path.of(".").toString() : parent.toString();
    }
}
