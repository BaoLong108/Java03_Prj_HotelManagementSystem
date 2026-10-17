package com.hotelpms.shared.config;

import com.hotelpms.auth.security.DatabaseJwtAuthenticationConverter;
import com.hotelpms.shared.api.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json,
            DatabaseJwtAuthenticationConverter converter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms", "/api/v1/rooms/{id}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/pricing/quote").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/room-blocks", "/api/v1/admin/room-blocks/{id}")
                            .hasAnyRole("STAFF", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/room-blocks").hasAnyRole("STAFF", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/room-blocks/{id}").hasAnyRole("STAFF", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/rooms/types", "/api/v1/admin/rooms/physical",
                                "/api/v1/admin/rooms/physical/{id}").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/rooms/types", "/api/v1/admin/rooms/physical")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/rooms/types/{id}", "/api/v1/admin/rooms/physical/{id}")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/rooms/types/{id}", "/api/v1/admin/rooms/physical/{id}")
                            .hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(json, response, 401, "UNAUTHORIZED", "Cần đăng nhập hoặc token không hợp lệ"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(json, response, 403, "FORBIDDEN", "Bạn không có quyền truy cập")))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(json, response, 401, "UNAUTHORIZED", "Cần đăng nhập hoặc token không hợp lệ"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(json, response, 403, "FORBIDDEN", "Bạn không có quyền truy cập")))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void writeError(ObjectMapper json, HttpServletResponse response, int status,
            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        json.writeValue(response.getOutputStream(), new ApiError(code, message));
    }
}
