package com.hotelpms.auth.config;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

/** Explicit, local-only, one-time command. Routine seeding never overwrites an existing password. */
@Component
@Order(100)
@Profile("dev & !prod & !production & !staging")
@ConditionalOnProperty(name = "app.demo-reset.enabled", havingValue = "true")
public class DevDemoPasswordReset implements ApplicationRunner {
    private static final String OPERATION = "demo-account-reset-2026-11-v1";
    private static final Map<String, String> ALLOWLIST = Map.of(
            "admin@hotel-pms.test", "ADMIN", "staff@hotel-pms.test", "STAFF", "customer@hotel-pms.test", "CUSTOMER");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String expectedDatabase;
    public DevDemoPasswordReset(JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${app.demo-reset.expected-database:}") String expectedDatabase) {
        this.jdbc = jdbc; this.encoder = encoder; this.expectedDatabase = expectedDatabase;
    }
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            URI uri = URI.create(connection.getMetaData().getURL().substring("jdbc:".length()));
            if (!java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost())
                    || expectedDatabase.isBlank() || !expectedDatabase.equals(connection.getCatalog())) {
                throw new IllegalStateException("Demo reset requires an explicitly named local development database");
            }
        }
        if (jdbc.update("INSERT INTO dev_demo_operations(operation_key) VALUES (?) ON CONFLICT DO NOTHING", OPERATION) == 0) return;
        for (var entry : ALLOWLIST.entrySet()) {
            String expectedName = "Demo " + entry.getValue().charAt(0) + entry.getValue().substring(1).toLowerCase(java.util.Locale.ROOT);
            var ids = jdbc.queryForList("""
                    SELECT u.id FROM app_users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
                    WHERE u.email=? AND u.full_name=? AND u.is_active=true AND r.code=?
                      AND (SELECT count(*) FROM user_roles WHERE user_id=u.id)=1
                    FOR UPDATE OF u
                    """, Long.class, entry.getKey(), expectedName, entry.getValue());
            if (ids.size() != 1) throw new IllegalStateException("Demo allowlist identity/role does not match: " + entry.getKey());
            jdbc.update("UPDATE app_users SET password_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    encoder.encode("123456"), ids.getFirst());
        }
    }
}
