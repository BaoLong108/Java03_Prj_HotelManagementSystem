package com.hotelpms.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.hotelpms.auth.config.DevDemoPasswordReset;
import com.hotelpms.auth.config.DemoDataSeeder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = DevDemoPasswordResetIntegrationTest.Cleanup.class, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class DevDemoPasswordResetIntegrationTest {
    private static EmbeddedPostgres postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws IOException {
        // Password mutations deliberately use a separate database, including on shared-DB CI.
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        properties.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        properties.add("spring.datasource.username", () -> "postgres"); properties.add("spring.datasource.password", () -> "postgres");
        properties.add("app.demo-seed.enabled", () -> "true"); properties.add("app.demo-seed.password", () -> "TestDemo123!");
    }
    public static class Cleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext context) throws IOException { if (postgres != null) postgres.close(); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DemoDataSeeder seeder;
    private static final Map<String,String> DEMOS = Map.of("admin", "ADMIN", "staff", "STAFF", "customer", "CUSTOMER");
    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM dev_demo_operations");
        for (String role : DEMOS.keySet()) {
            String name = "Demo " + Character.toUpperCase(role.charAt(0)) + role.substring(1);
            jdbc.update("UPDATE app_users SET full_name=?,password_hash=? WHERE email=?", name, encoder.encode("TestDemo123!"), role + "@hotel-pms.test");
        }
    }
    private void reset(String database) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            try { new DevDemoPasswordReset(jdbc, encoder, database).run(new DefaultApplicationArguments()); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
    }
    private String login(String email, String password, int status) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("email",email,"password",password))))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return status == 200 ? json.readTree(response).get("accessToken").asText() : null;
    }
    @Test void resetsOnlyAllowlistedDemoIdentitiesUsingBcryptAndNormalJwt() throws Exception {
        reset("postgres");
        for (var role : DEMOS.entrySet()) {
            String email = role.getKey() + "@hotel-pms.test";
            String hash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE email=?", String.class, email);
            assertThat(hash.startsWith("$2") && encoder.matches("123456", hash)).isTrue();
            String token = login(email, "123456", 200);
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value(role.getValue()));
        }
    }
    @Test void repeatedExplicitCommandAndRoutineSeederPreserveManualPasswordChanges() throws Exception {
        reset("postgres");
        jdbc.update("UPDATE app_users SET password_hash=? WHERE email='admin@hotel-pms.test'", encoder.encode("ManualDemoOnly123!"));
        reset("postgres"); seeder.run(new DefaultApplicationArguments());
        login("admin@hotel-pms.test", "ManualDemoOnly123!", 200); login("admin@hotel-pms.test", "123456", 401);
        login("staff@hotel-pms.test", "123456", 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_demo_operations", Integer.class)).isEqualTo(1);
    }
    @Test void preservesUnrelatedCustomerAndKeepsNormalPasswordPolicy() throws Exception {
        String email = "preserved-person@example.test";
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(json.writeValueAsBytes(Map.of(
                "email",email,"password","CustomerKeep123!","fullName","Unrelated QA Customer","phoneNumber","0901234567"))))
                .andExpect(status().isCreated());
        reset("postgres"); login(email, "CustomerKeep123!", 200); login(email, "123456", 401);
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(json.writeValueAsBytes(Map.of(
                "email","weak@example.test","password","123456","fullName","Weak Test","phoneNumber","0901234567"))))
                .andExpect(status().isBadRequest());
    }
    @Test void mismatchedDatabaseOrDemoIdentityRollsBackTheEntireOperation() {
        assertThatThrownBy(() -> reset("another_database")).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE app_users SET full_name='Different account owner' WHERE email='customer@hotel-pms.test'");
        assertThatThrownBy(() -> reset("postgres")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_demo_operations", Integer.class)).isZero();
        for (String role : DEMOS.keySet()) assertThat(encoder.matches("TestDemo123!", jdbc.queryForObject(
                "SELECT password_hash FROM app_users WHERE email=?", String.class, role + "@hotel-pms.test"))).isTrue();
    }
    @Test void explicitResetBeanIsUnavailableInProduction() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("explicit-command", Map.of("app.demo-reset.enabled", true)));
            context.register(DevDemoPasswordReset.class); context.refresh();
            assertThat(context.getBeanNamesForType(DevDemoPasswordReset.class)).isEmpty();
        }
    }
}
