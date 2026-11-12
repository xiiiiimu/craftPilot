package com.craftpilot.infrastructure;

import com.craftpilot.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, Environment environment, JdbcClient jdbc,
                                 PasswordEncoder encoder, ObjectMapper mapper,
                                 @Value("${craftpilot.auth.dev-enabled}") boolean devEnabled,
                                 @Value("${server.address}") String address) throws Exception {
        if (devEnabled && (!Arrays.equals(environment.getActiveProfiles(), new String[]{"dev"})
                || !("127.0.0.1".equals(address) || "::1".equals(address)))) {
            throw new IllegalStateException("Development authentication requires the exclusive dev profile and a loopback address");
        }

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> {
                            if (devEnabled) {
                                response.setHeader("WWW-Authenticate", "Basic realm=\"CraftPilot local development\"");
                            }
                            writeError(response, mapper, 401, "UNAUTHENTICATED", "Authentication required");
                        })
                        .accessDeniedHandler((request, response, failure) ->
                                writeError(response, mapper, 403, "FORBIDDEN", "Insufficient permissions")));

        if (devEnabled) {
            var provider = new DaoAuthenticationProvider(devUsers(jdbc));
            provider.setPasswordEncoder(encoder);
            http.authenticationProvider(provider).httpBasic(basic -> basic
                    .authenticationEntryPoint((request, response, failure) -> {
                        if (failure instanceof org.springframework.security.authentication.InternalAuthenticationServiceException) {
                            writeError(response, mapper, 503, "AUTH_UNAVAILABLE", "Authentication database unavailable");
                            return;
                        }
                        response.setHeader("WWW-Authenticate", "Basic realm=\"CraftPilot local development\"");
                        writeError(response, mapper, 401, "UNAUTHENTICATED", "Authentication required");
                    }));
        }
        return http.build();
    }

    private UserDetailsService devUsers(JdbcClient jdbc) {
        return login -> {
            if (login.length() > 120) {
                throw new UsernameNotFoundException("Invalid credentials");
            }
            return jdbc.sql("""
                    SELECT p.id, c.password_hash
                    FROM principal p JOIN dev_credential c ON c.principal_id = p.id
                    WHERE p.login = :login AND p.enabled AND c.expires_at > clock_timestamp()
                    """).param("login", login.toLowerCase(Locale.ROOT))
                    .query((rs, row) -> User.withUsername(rs.getObject("id", UUID.class).toString())
                            .password(rs.getString("password_hash")).roles("AUTHENTICATED").build())
                    .optional().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        };
    }

    private void writeError(HttpServletResponse response, ObjectMapper mapper, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), ApiError.of(code, message, status == 503));
    }
}
