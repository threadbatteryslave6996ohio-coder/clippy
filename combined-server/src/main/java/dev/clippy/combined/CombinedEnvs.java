package dev.clippy.combined;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvFiles;
import dev.clippy.utils.envmanager.EnvOption;
import dev.clippy.utils.envmanager.EnvSchema;
import dev.clippy.utils.envmanager.EnvType;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;

public final class CombinedEnvs {
    public static final EnvOption<String> COMBINED_SERVER_PORT;
    public static final EnvOption<String> AUTH_DATASOURCE_URL;
    public static final EnvOption<String> AUTH_DATASOURCE_USERNAME;
    public static final EnvOption<String> AUTH_DATASOURCE_PASSWORD;
    public static final EnvOption<String> SPRING_DATASOURCE_URL;
    public static final EnvOption<String> SPRING_DATASOURCE_USERNAME;
    public static final EnvOption<String> SPRING_DATASOURCE_PASSWORD;
    public static final EnvOption<String> CLIPPY_AUTH_BASE_URL;
    public static final EnvOption<String> CLIPPY_AUTH_ROUTE_PREFIX;
    public static final EnvOption<String> CLIPPY_SERVER_ROUTE_PREFIX;
    public static final EnvOption<String> LOGGING_FILE_NAME;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        COMBINED_SERVER_PORT = builder.optional("COMBINED_SERVER_PORT", EnvType.string(), "8080");
        AUTH_DATASOURCE_URL = builder.optional("AUTH_DATASOURCE_URL", EnvType.string(),
                "jdbc:postgresql://localhost:5433/auth");
        AUTH_DATASOURCE_USERNAME = builder.optional("AUTH_DATASOURCE_USERNAME", EnvType.string(), "auth");
        AUTH_DATASOURCE_PASSWORD = builder.optional("AUTH_DATASOURCE_PASSWORD", EnvType.string(), "auth");
        SPRING_DATASOURCE_URL = builder.optional("SPRING_DATASOURCE_URL", EnvType.string(),
                "jdbc:postgresql://localhost:5432/clippy");
        SPRING_DATASOURCE_USERNAME = builder.optional("SPRING_DATASOURCE_USERNAME", EnvType.string(), "clippy");
        SPRING_DATASOURCE_PASSWORD = builder.optional("SPRING_DATASOURCE_PASSWORD", EnvType.string(), "clippy");
        CLIPPY_AUTH_BASE_URL = builder.optional("CLIPPY_AUTH_BASE_URL", EnvType.string(), "http://localhost:8080/auth");
        CLIPPY_AUTH_ROUTE_PREFIX = builder.optional("CLIPPY_AUTH_ROUTE_PREFIX", EnvType.string(), "/auth");
        CLIPPY_SERVER_ROUTE_PREFIX = builder.optional("CLIPPY_SERVER_ROUTE_PREFIX", EnvType.string(), "/api");
        LOGGING_FILE_NAME = builder.optional("LOGGING_FILE_NAME", EnvType.string(), "logs/clippy-combined-server.log");
        ENV = builder.build();
    }

    private CombinedEnvs() {
    }

    public static Env load() throws IOException {
        String explicitFile = System.getenv("CLIPPY_ENV_FILE");
        Path envFile = explicitFile != null && !explicitFile.isBlank()
                ? Path.of(explicitFile.trim())
                : defaultEnvFile();
        return from(EnvFiles.loadRequiredFile(envFile));
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    static Path defaultEnvFile() throws IOException {
        try {
            Path codeSourceLocation = Path.of(
                    CombinedEnvs.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
            Path moduleDirectory = codeSourceLocation.getParent() == null
                    ? null
                    : codeSourceLocation.getParent().getParent();
            if (moduleDirectory == null) {
                throw new IOException("Unable to resolve combined-server module directory from " + codeSourceLocation);
            }
            return moduleDirectory.resolve(".env");
        } catch (URISyntaxException e) {
            throw new IOException("Unable to resolve combined-server env file location", e);
        }
    }
}
