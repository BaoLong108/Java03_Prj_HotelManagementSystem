package com.hotelpms.auth.service;

import com.hotelpms.auth.config.JwtProperties;
import com.hotelpms.auth.dto.AuthResponse;
import com.hotelpms.auth.dto.UserResponse;
import com.hotelpms.auth.entity.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public JwtService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public AuthResponse issue(User user) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UserResponse userResponse = UserResponse.from(user);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer()).subject(user.getId().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(properties.accessTokenTtlSeconds()))
                .claim("roles", userResponse.roles().stream().map(Enum::name).toList()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new AuthResponse(token, "Bearer", properties.accessTokenTtlSeconds(), userResponse);
    }
}
