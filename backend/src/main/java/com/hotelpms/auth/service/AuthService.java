package com.hotelpms.auth.service;

import com.hotelpms.auth.dto.AuthResponse;
import com.hotelpms.auth.dto.LoginRequest;
import com.hotelpms.auth.dto.RegisterRequest;
import com.hotelpms.auth.dto.UserResponse;
import com.hotelpms.auth.entity.CustomerProfile;
import com.hotelpms.auth.entity.Role;
import com.hotelpms.auth.entity.RoleCode;
import com.hotelpms.auth.entity.User;
import com.hotelpms.auth.repository.CustomerProfileRepository;
import com.hotelpms.auth.repository.RoleRepository;
import com.hotelpms.auth.repository.UserRepository;
import com.hotelpms.shared.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final CustomerProfileRepository profiles;
    private final PasswordEncoder passwords;
    private final JwtService tokens;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, RoleRepository roles, CustomerProfileRepository profiles,
            PasswordEncoder passwords, JwtService tokens) {
        this.users = users;
        this.roles = roles;
        this.profiles = profiles;
        this.passwords = passwords;
        this.tokens = tokens;
        // Unknown accounts still perform a password comparison before returning the same error.
        this.dummyPasswordHash = passwords.encode("not-a-real-account-password");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email đã được đăng ký");
        }
        Role customerRole = roles.findByCode(RoleCode.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role is missing; verify Flyway migration"));
        User user = users.saveAndFlush(new User(email, passwords.encode(request.password()),
                request.fullName().strip(), Set.of(customerRole)));
        String phone = request.phoneNumber() == null ? null : request.phoneNumber().strip();
        profiles.save(new CustomerProfile(user, phone == null || phone.isEmpty() ? null : phone));
        return tokens.issue(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = users.findByEmailIgnoreCase(request.email().strip()).orElse(null);
        boolean validLength = request.password().getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matched = passwords.matches(validLength ? request.password() : "invalid-password-length",
                user == null ? dummyPasswordHash : user.getPasswordHash());
        if (!validLength || !matched || user == null || !user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng");
        }
        return tokens.issue(user);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated() and @authorizationPolicy.isCurrentUser(authentication, #p0)")
    public UserResponse me(Long userId) {
        User user = users.findById(userId).filter(User::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED", "Tài khoản không còn khả dụng"));
        return UserResponse.from(user);
    }
}
