package dev.jbringb.resume_scope.config;

import io.r2dbc.spi.ConnectionFactory;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PersistenceConfiguration {

    /**
     * jOOQ {@link DSLContext} bound to the R2DBC {@link ConnectionFactory}, so queries execute
     * non-blocking and return Reactive Streams {@code Publisher}s. Defining this bean makes
     * Spring Boot's JDBC-backed jOOQ auto-configuration back off ({@code @ConditionalOnMissingBean}).
     * Flyway still uses the JDBC {@code DataSource} for migrations at startup.
     */
    @Bean
    DSLContext dslContext(ConnectionFactory connectionFactory) {
        return DSL.using(connectionFactory, SQLDialect.POSTGRES);
    }
}
