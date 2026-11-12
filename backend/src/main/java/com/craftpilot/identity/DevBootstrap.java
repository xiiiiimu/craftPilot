package com.craftpilot.identity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "craftpilot.auth.dev-enabled", havingValue = "true")
class DevBootstrap implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String login;
    private final String password;

    DevBootstrap(JdbcClient jdbc, PasswordEncoder encoder,
                 @Value("${CRAFTPILOT_BOOTSTRAP_LOGIN:}") String login,
                 @Value("${CRAFTPILOT_BOOTSTRAP_PASSWORD:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.login = login.toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (login.isEmpty() && password.isEmpty()) {
            return;
        }
        if (!login.matches("[a-z0-9][a-z0-9._-]{2,119}") || password.length() < 16
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Invalid development bootstrap login or password length");
        }
        UUID principal = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO principal (id, login, display_name) VALUES (:id, :login, 'Local developer')
                ON CONFLICT (login) DO NOTHING
                """).param("id", principal).param("login", login).update();
        if (inserted == 0) {
            throw new IllegalStateException("Development bootstrap login already exists; omit bootstrap settings or choose a new login");
        }
        jdbc.sql("INSERT INTO merchant (id, name) VALUES (:id, 'Local development')")
                .param("id", merchant).update();
        jdbc.sql("""
                INSERT INTO merchant_member (merchant_id, principal_id, role_code) VALUES (:merchant, :principal, 'OWNER')
                """).param("merchant", merchant).param("principal", principal).update();
        jdbc.sql("""
                INSERT INTO dev_credential (principal_id, password_hash, expires_at)
                VALUES (:id, :hash, current_timestamp + interval '24 hours')
                """).param("id", principal).param("hash", encoder.encode(password)).update();
    }
}
