package dev.clippy.combined;

import com.zaxxer.hikari.HikariDataSource;
import dev.clippy.server.ClipboardEntry;
import dev.clippy.server.ClipboardEntryRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableJpaRepositories(
        basePackageClasses = ClipboardEntryRepository.class,
        entityManagerFactoryRef = "clipboardEntityManagerFactory",
        transactionManagerRef = "clipboardTransactionManager"
)
class CombinedClipboardDatabaseConfiguration {
    @Bean(name = "clipboardDataSource")
    @Primary
    DataSource clipboardDataSource(Environment environment) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(environment.getRequiredProperty("spring.datasource.url"))
                .username(environment.getRequiredProperty("spring.datasource.username"))
                .password(environment.getRequiredProperty("spring.datasource.password"))
                .build();
    }

    @Bean(name = "clipboardEntityManagerFactory")
    @Primary
    LocalContainerEntityManagerFactoryBean clipboardEntityManagerFactory(
            @Qualifier("clipboardDataSource") DataSource dataSource,
            Environment environment
    ) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(ClipboardEntry.class.getPackageName());
        factory.setPersistenceUnitName("clipboard");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(jpaProperties(environment, "clippy.clipboard.jpa.hibernate.ddl-auto"));
        return factory;
    }

    @Bean(name = "clipboardTransactionManager")
    @Primary
    PlatformTransactionManager clipboardTransactionManager(
            @Qualifier("clipboardEntityManagerFactory") EntityManagerFactory entityManagerFactory
    ) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    private static Map<String, Object> jpaProperties(Environment environment, String ddlAutoProperty) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(AvailableSettings.HBM2DDL_AUTO, environment.getRequiredProperty(ddlAutoProperty));
        properties.put("hibernate.jdbc.time_zone", environment.getRequiredProperty("clippy.jpa.jdbc-time-zone"));
        return properties;
    }
}
