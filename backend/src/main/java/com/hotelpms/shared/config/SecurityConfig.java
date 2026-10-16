package com.hotelpms.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cấu hình bảo mật tạm thời dùng HTTP Basic Auth cho admin endpoints.
 * TODO M1-03: thay thế bằng JWT filter khi auth module hoàn thiện.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(basic -> basic.realmName("Hotel PMS"))
                .authorizeHttpRequests(auth -> auth
                        // Health check — public
                        .requestMatchers("/actuator/health").permitAll()
                        // Public room listing — GET only, no auth needed
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms", "/api/v1/rooms/**").permitAll()
                        // Admin CRUD — yêu cầu ADMIN role
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Tất cả còn lại — từ chối
                        .anyRequest().denyAll()
                )
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * User tạm thời trong memory để test admin API.
     * TODO M1-03: xóa bean này khi có UserDetailsService từ database.
     */
    @Bean
    UserDetailsService userDetailsService(PasswordEncoder encoder) {
        var admin = User.builder()
                .username("admin")
                .password(encoder.encode("admin123"))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }
}
