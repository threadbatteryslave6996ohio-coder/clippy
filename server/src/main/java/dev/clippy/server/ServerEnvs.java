package dev.clippy.server;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvFiles;
import dev.clippy.utils.envmanager.EnvOption;
import dev.clippy.utils.envmanager.EnvSchema;
import dev.clippy.utils.envmanager.EnvType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class ServerEnvs {
    public static final EnvOption<String> SPRING_DATASOURCE_URL;
    public static final EnvOption<String> SPRING_DATASOURCE_USERNAME;
    public static final EnvOption<String> SPRING_DATASOURCE_PASSWORD;
    public static final EnvOption<String> SERVER_PORT;
    public static final EnvOption<String> CLIPPY_AUTH_BASE_URL;
    public static final EnvOption<String> LOGGING_FILE_NAME;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        SPRING_DATASOURCE_URL = builder.optional("SPRING_DATASOURCE_URL", EnvType.string(), "jdbc:postgresql://localhost:5432/clippy");
        SPRING_DATASOURCE_USERNAME = builder.optional("SPRING_DATASOURCE_USERNAME", EnvType.string(), "clippy");
        SPRING_DATASOURCE_PASSWORD = builder.optional("SPRING_DATASOURCE_PASSWORD", EnvType.string(), "clippy");
        SERVER_PORT = builder.optional("SERVER_PORT", EnvType.string(), "8080");
        CLIPPY_AUTH_BASE_URL = builder.optional("CLIPPY_AUTH_BASE_URL", EnvType.string(), "http://localhost:8081");
        LOGGING_FILE_NAME = builder.optional("LOGGING_FILE_NAME", EnvType.string(), "logs/clippy-server.log");
        ENV = builder.build();
    }

    private ServerEnvs() {
    }

    public static Env load() throws IOException {
        return from(EnvFiles.loadDotenvOnly(Path.of("").toAbsolutePath()));
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springDefaults(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("SPRING_DATASOURCE_URL", env.get(SPRING_DATASOURCE_URL));
        values.put("SPRING_DATASOURCE_USERNAME", env.get(SPRING_DATASOURCE_USERNAME));
        values.put("SPRING_DATASOURCE_PASSWORD", env.get(SPRING_DATASOURCE_PASSWORD));
        values.put("SERVER_PORT", env.get(SERVER_PORT));
        values.put("CLIPPY_AUTH_BASE_URL", env.get(CLIPPY_AUTH_BASE_URL));
        values.put("LOGGING_FILE_NAME", env.get(LOGGING_FILE_NAME));
        return values;
    }
}
