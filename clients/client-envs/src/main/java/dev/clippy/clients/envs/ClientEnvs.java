package dev.clippy.clients.envs;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvOption;
import dev.clippy.utils.envmanager.EnvSchema;
import dev.clippy.utils.envmanager.EnvType;

import java.util.Map;

public final class ClientEnvs {
    public static final EnvOption<String> REMOTE_SERVER_URL;
    public static final EnvOption<String> CLIENT_ID;
    public static final EnvOption<Long> CLIPBOARD_POLL_INTERVAL_MS;
    public static final EnvOption<String> CLIPBOARD_BACKEND;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        REMOTE_SERVER_URL = builder.required("REMOTE_SERVER_URL", EnvType.string());
        CLIENT_ID = builder.optional("CLIENT_ID", EnvType.string());
        CLIPBOARD_POLL_INTERVAL_MS = builder.optional("CLIPBOARD_POLL_INTERVAL_MS", EnvType.longInteger());
        CLIPBOARD_BACKEND = builder.optional("CLIPBOARD_BACKEND", EnvType.string());
        ENV = builder.build();
    }

    private ClientEnvs() {
    }

    public static Env fromSystem() {
        return ENV.fromSystem();
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }
}
