package dev.clippy.auth;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvOption;
import dev.clippy.utils.envmanager.EnvSchema;
import dev.clippy.utils.envmanager.EnvType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class AuthServerEnvs {
    private static final String DOTENV_FILE = ".env";

    public static final EnvOption<String> AUTH_DATASOURCE_URL;
    public static final EnvOption<String> AUTH_DATASOURCE_USERNAME;
    public static final EnvOption<String> AUTH_DATASOURCE_PASSWORD;
    public static final EnvOption<String> AUTH_SERVER_PORT;
    public static final EnvOption<String> AUTH_LOGGING_FILE_NAME;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        AUTH_DATASOURCE_URL = builder.optional("AUTH_DATASOURCE_URL", EnvType.string(), "jdbc:postgresql://localhost:5433/auth");
        AUTH_DATASOURCE_USERNAME = builder.optional("AUTH_DATASOURCE_USERNAME", EnvType.string(), "auth");
        AUTH_DATASOURCE_PASSWORD = builder.optional("AUTH_DATASOURCE_PASSWORD", EnvType.string(), "auth");
        AUTH_SERVER_PORT = builder.optional("AUTH_SERVER_PORT", EnvType.string(), "8081");
        AUTH_LOGGING_FILE_NAME = builder.optional("AUTH_LOGGING_FILE_NAME", EnvType.string(), "logs/clippy-auth-server.log");
        ENV = builder.build();
    }

    private AuthServerEnvs() {
    }

    public static Env fromSystem() {
        return ENV.fromSystem();
    }

    public static Env load() throws IOException {
        return loadFrom(Path.of("").toAbsolutePath());
    }

    static Env loadFrom(Path startDirectory) throws IOException {
        return from(loadConfig(startDirectory));
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springDefaults(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("AUTH_DATASOURCE_URL", env.get(AUTH_DATASOURCE_URL));
        values.put("AUTH_DATASOURCE_USERNAME", env.get(AUTH_DATASOURCE_USERNAME));
        values.put("AUTH_DATASOURCE_PASSWORD", env.get(AUTH_DATASOURCE_PASSWORD));
        values.put("AUTH_SERVER_PORT", env.get(AUTH_SERVER_PORT));
        values.put("AUTH_LOGGING_FILE_NAME", env.get(AUTH_LOGGING_FILE_NAME));
        return values;
    }

    private static Map<String, String> loadConfig(Path startDirectory) throws IOException {
        Map<String, String> values = new HashMap<>(loadDotenv(startDirectory));
        System.getenv().forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                values.put(name, value);
            }
        });
        return values;
    }

    private static Map<String, String> loadDotenv(Path startDirectory) throws IOException {
        Path path = findDotenv(startDirectory);
        if (path == null) {
            return Map.of();
        }

        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }

            String key = trimmed.substring(0, separator).trim();
            String value = unquote(trimmed.substring(separator + 1).trim());
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static Path findDotenv(Path startDirectory) {
        Path directory = startDirectory;
        while (directory != null) {
            Path candidate = directory.resolve(DOTENV_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
