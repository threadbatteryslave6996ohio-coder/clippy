package dev.clippy.combined;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvFiles;
import dev.clippy.utils.envmanager.EnvOption;
import dev.clippy.utils.envmanager.EnvSchema;
import dev.clippy.utils.envmanager.EnvType;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        return loadFromFile(envFile);
    }

    static Env loadFromFile(Path envFile) throws IOException {
        return from(EnvFiles.loadRequiredFile(envFile));
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    static Map<String, Object> springDefaults(Env env) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("server.port", env.get(COMBINED_SERVER_PORT));
        values.put("clippy.auth.datasource.url", env.get(AUTH_DATASOURCE_URL));
        values.put("clippy.auth.datasource.username", env.get(AUTH_DATASOURCE_USERNAME));
        values.put("clippy.auth.datasource.password", env.get(AUTH_DATASOURCE_PASSWORD));
        values.put("spring.datasource.url", env.get(SPRING_DATASOURCE_URL));
        values.put("spring.datasource.username", env.get(SPRING_DATASOURCE_USERNAME));
        values.put("spring.datasource.password", env.get(SPRING_DATASOURCE_PASSWORD));
        values.put("clippy.auth.base-url", env.get(CLIPPY_AUTH_BASE_URL));
        values.put("clippy.auth.route-prefix", env.get(CLIPPY_AUTH_ROUTE_PREFIX));
        values.put("clippy.server.route-prefix", env.get(CLIPPY_SERVER_ROUTE_PREFIX));
        values.put("logging.file.name", env.get(LOGGING_FILE_NAME));
        return values;
    }

    static Path defaultEnvFile() throws IOException {
        try {
            Path codeSourceLocation = Path.of(
                    CombinedEnvs.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
            Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            return defaultEnvFile(codeSourceLocation, workingDirectory);
        } catch (URISyntaxException e) {
            throw new IOException(missingEnvFileMessage(), e);
        }
    }

    static Path defaultEnvFile(Path codeSourceLocation) throws IOException {
        Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        return defaultEnvFile(codeSourceLocation, workingDirectory);
    }

    static Path defaultEnvFile(Path codeSourceLocation, Path workingDirectory) throws IOException {
        Path workingDirectoryEnvFile = workingDirectory.resolve(".env");
        if (Files.isRegularFile(workingDirectoryEnvFile)) {
            return workingDirectoryEnvFile;
        }
        Path moduleDirectory = codeSourceLocation.getParent() == null
                ? null
                : codeSourceLocation.getParent().getParent();
        if (moduleDirectory == null) {
            throw new IOException(missingEnvFileMessage());
        }
        return moduleDirectory.resolve(".env");
    }

    private static String missingEnvFileMessage() {
        return "Missing combined server env file. Create combined-server/.env or set CLIPPY_ENV_FILE to its absolute path.";
    }
}
