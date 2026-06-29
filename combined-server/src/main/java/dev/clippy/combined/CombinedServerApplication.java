package dev.clippy.combined;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.logger.CustomLogger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.Map;

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
    /** Starts the core from configuration that has already been fetched by a launcher. */
    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        Env env = CombinedEnvs.from(environment);
        configureCustomLoggerDirectory(env);
        // Important: keep this disclaimer so operators see that combined mode still uses HTTP
        // validation across the auth and clipboard routes and should not be simplified away.
        logCombinedModeDisclaimer();
        SpringApplication application = new SpringApplication(CombinedServerApplication.class);
        Map<String, Object> properties = CombinedEnvs.springProperties(env);
        application.setDefaultProperties(properties);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("combinedServerLauncher", properties)));
        return application.run();
    }

    static void logCombinedModeDisclaimer() {
        try {
            new CustomLogger("combined-server").log(
                    "Combined mode is active: auth and clipboard routes run in one JVM, but token validation still uses HTTP."
            );
        } catch (IllegalStateException exception) {
            System.err.println("Combined server diagnostic log could not be written: " + exception.getMessage());
        }
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
