package com.hotelpms.shared.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Khoi dong embedded PostgreSQL khi spring.datasource.embedded=true.
 * Dung cho dev local khong can cai Docker hay PostgreSQL.
 * Profile prod/staging dung PostgreSQL that qua DB_URL env var.
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.embedded", havingValue = "true")
public class EmbeddedPostgresConfig {
    private final int port;

    public EmbeddedPostgresConfig(@Value("${app.dev-postgres.port:0}") int port) {
        this.port = port;
    }

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder()
                .setPort(port)
                .start();
    }

    @Bean
    @Primary
    public DataSource embeddedDataSource(EmbeddedPostgres pg) throws SQLException {
        // The new isolated cluster has only the postgres database initially.
        try (var connection = pg.getPostgresDatabase().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE hotel_pms");
        }
        return pg.getDatabase("postgres", "hotel_pms");
    }
}
