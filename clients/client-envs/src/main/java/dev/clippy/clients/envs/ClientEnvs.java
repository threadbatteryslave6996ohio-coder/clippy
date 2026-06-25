package dev.clippy.clients.envs;

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

public final class ClientEnvs {
    private static final String DOTENV_FILE = ".env";

    public static final EnvOption<String> REMOTE_SERVER_URL;
    public static final EnvOption<String> CLIENT_ID;
    public static final EnvOption<String> CLIENT_TOKEN;
    public static final EnvOption<Long> CLIPBOARD_POLL_INTERVAL_MS;
    public static final EnvOption<String> CLIPBOARD_BACKEND;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        REMOTE_SERVER_URL = builder.required("REMOTE_SERVER_URL", EnvType.string());
        CLIENT_ID = builder.optional("CLIENT_ID", EnvType.string());
        CLIENT_TOKEN = builder.required("CLIENT_TOKEN", EnvType.string());
        CLIPBOARD_POLL_INTERVAL_MS = builder.optional("CLIPBOARD_POLL_INTERVAL_MS", EnvType.longInteger());
        CLIPBOARD_BACKEND = builder.optional("CLIPBOARD_BACKEND", EnvType.string());
        ENV = builder.build();
    }

    private ClientEnvs() {
    }

    public static Env fromSystem() {
        return ENV.fromSystem();
    }

    public static Env load() throws IOException {
        return from(loadConfig());
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    private static Map<String, String> loadConfig() throws IOException {
        Map<String, String> values = new HashMap<>(loadDotenv());
        System.getenv().forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                values.put(name, value);
            }
        });
        return values;
    }

    private static Map<String, String> loadDotenv() throws IOException {
        Path path = findDotenv();
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

    private static Path findDotenv() {
        Path directory = Path.of("").toAbsolutePath();
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
