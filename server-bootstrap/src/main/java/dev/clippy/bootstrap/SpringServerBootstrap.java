package dev.clippy.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.Objects;

public final class SpringServerBootstrap {
    private SpringServerBootstrap() {
    }

    public static ConfigurableApplicationContext run(
            Class<?> applicationClass,
            Map<String, Object> properties,
            String propertySourceName
    ) {
        Objects.requireNonNull(applicationClass, "applicationClass");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(propertySourceName, "propertySourceName");

        SpringApplication application = new SpringApplication(applicationClass);
        application.setDefaultProperties(properties);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(propertySourceName, properties)));
        return application.run();
    }
}
