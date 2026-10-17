package com.hotelpms.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class EmbeddedPostgresConfigTest {
    @Test
    void embeddedDatasourceConnectsToTheNamedDatabaseAndAppliesTheSharedMigrations() throws Exception {
        var configuration = new EmbeddedPostgresConfig(0);
        try (var postgres = configuration.embeddedPostgres()) {
            var datasource = configuration.embeddedDataSource(postgres);
            try (var connection = datasource.getConnection(); var query = connection.createStatement();
                 var result = query.executeQuery("SELECT current_database()")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("hotel_pms");
            }
            var flyway = Flyway.configure().dataSource(datasource).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            flyway.validate();
        }
    }
}
