package com.hotelpms.auth.config;

import com.hotelpms.auth.entity.CustomerProfile;
import com.hotelpms.auth.entity.RoleCode;
import com.hotelpms.auth.entity.User;
import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.auth.repository.RoleRepository;
import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.auth.validation.PasswordValidator;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.demo-seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {
    private final UserRepository users;
    private final RoleRepository roles;
    private final CustomerProfileRepository profiles;
    private final PasswordEncoder passwords;
    private final String demoPassword;

    public DemoDataSeeder(UserRepository users, RoleRepository roles, CustomerProfileRepository profiles,
            PasswordEncoder passwords, @Value("${app.demo-seed.password}") String demoPassword) {
        this.users = users;
        this.roles = roles;
        this.profiles = profiles;
        this.passwords = passwords;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!new PasswordValidator().isValid(demoPassword, null)) {
            throw new IllegalArgumentException("DEMO_SEED_PASSWORD must satisfy the registration password policy");
        }
        seed("customer@hotel-pms.test", "Demo Customer", RoleCode.CUSTOMER);
        seed("staff@hotel-pms.test", "Demo Staff", RoleCode.STAFF);
        seed("admin@hotel-pms.test", "Demo Admin", RoleCode.ADMIN);
    }

    private void seed(String email, String name, RoleCode code) {
        if (users.existsByEmailIgnoreCase(email)) return;
        var role = roles.findByCode(code).orElseThrow();
        User user = users.saveAndFlush(new User(email, passwords.encode(demoPassword), name, Set.of(role)));
        if (code == RoleCode.CUSTOMER) profiles.save(new CustomerProfile(user, null));
    }
}
