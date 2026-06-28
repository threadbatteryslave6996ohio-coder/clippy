package dev.clippy.combined;

import dev.clippy.utils.envmanager.Env;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

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
    public static void main(String[] args) throws IOException {
        Env env = CombinedEnvs.load();
        applySpringSystemProperties(env);
        configureCustomLoggerDirectory(env);
        SpringApplication application = new SpringApplication(CombinedServerApplication.class);
        application.run(args);
    }

    private static void applySpringSystemProperties(Env env) {
        System.setProperty("server.port", env.get(CombinedEnvs.COMBINED_SERVER_PORT));
        System.setProperty("AUTH_DATASOURCE_URL", env.get(CombinedEnvs.AUTH_DATASOURCE_URL));
        System.setProperty("AUTH_DATASOURCE_USERNAME", env.get(CombinedEnvs.AUTH_DATASOURCE_USERNAME));
        System.setProperty("AUTH_DATASOURCE_PASSWORD", env.get(CombinedEnvs.AUTH_DATASOURCE_PASSWORD));
        System.setProperty("SPRING_DATASOURCE_URL", env.get(CombinedEnvs.SPRING_DATASOURCE_URL));
        System.setProperty("SPRING_DATASOURCE_USERNAME", env.get(CombinedEnvs.SPRING_DATASOURCE_USERNAME));
        System.setProperty("SPRING_DATASOURCE_PASSWORD", env.get(CombinedEnvs.SPRING_DATASOURCE_PASSWORD));
        System.setProperty("clippy.auth.base-url", env.get(CombinedEnvs.CLIPPY_AUTH_BASE_URL));
        System.setProperty("clippy.auth.route-prefix", env.get(CombinedEnvs.CLIPPY_AUTH_ROUTE_PREFIX));
        System.setProperty("clippy.server.route-prefix", env.get(CombinedEnvs.CLIPPY_SERVER_ROUTE_PREFIX));
        System.setProperty("logging.file.name", env.get(CombinedEnvs.LOGGING_FILE_NAME));
    }

    private static void configureCustomLoggerDirectory(Env env) {
        Set<String> directories = new LinkedHashSet<>();
        directories.add(parentDirectory(env.get(CombinedEnvs.LOGGING_FILE_NAME)));
        directories.add(parentDirectory(env.get(CombinedEnvs.AUTH_LOGGING_FILE_NAME)));
        directories.remove(null);

        if (directories.size() > 1) {
            throw new IllegalStateException("Combined mode requires LOGGING_FILE_NAME and AUTH_LOGGING_FILE_NAME to share a parent directory.");
        }

        String customLoggerDir = directories.isEmpty() ? Path.of(".").toString() : directories.iterator().next();
        System.setProperty("custom.logger.dir", customLoggerDir);
    }

    private static String parentDirectory(String loggingFileName) {
        if (loggingFileName == null || loggingFileName.trim().isEmpty()) {
            return null;
        }
        Path path = Path.of(loggingFileName.trim());
        Path parent = path.getParent();
        return parent == null ? Path.of(".").toString() : parent.toString();
    }
}
