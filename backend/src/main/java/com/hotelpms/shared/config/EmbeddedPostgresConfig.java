package com.hotelpms.shared.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
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

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder()
                .setPort(5432)
                .start();
    }

    @Bean
    @Primary
    public DataSource embeddedDataSource(EmbeddedPostgres pg) {
        return pg.getPostgresDatabase("hotel_pms");
    }
}