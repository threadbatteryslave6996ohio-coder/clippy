package dev.clippy.server;

import dev.clippy.utils.envmanager.Env;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.Map;

@SpringBootApplication
public class ClippyServerApplication {
    /**
     * Boots the clipboard server from an already-resolved environment. The core never reads
     * {@code .env} files or system env itself: whoever launches it (see {@link ClippyServerLauncher},
     * the combined server, or tests) decides how to fetch the values and passes them in here.
     */
    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        Env env = ServerEnvs.from(environment);
        configureCustomLoggerDirectory(env);
        SpringApplication application = new SpringApplication(ClippyServerApplication.class);
        Map<String, Object> properties = ServerEnvs.springProperties(env);
        application.setDefaultProperties(properties);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("clippyServerLauncher", properties)));
        return application.run();
    }

    private static void configureCustomLoggerDirectory(Env env) {
        String loggingFileName = env.get(ServerEnvs.LOGGING_FILE_NAME);
        Path loggingPath = Path.of(loggingFileName == null ? "" : loggingFileName.trim());
        Path parentDirectory = loggingPath.getParent();
        String customLoggerDir = parentDirectory == null ? Path.of(".").toString() : parentDirectory.toString();
        System.setProperty("custom.logger.dir", customLoggerDir);
    }
}
