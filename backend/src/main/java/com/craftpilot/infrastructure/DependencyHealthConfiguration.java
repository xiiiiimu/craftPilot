package com.craftpilot.infrastructure;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class DependencyHealthConfiguration {
    @Bean
    MinioClient minioClient(@Value("${craftpilot.minio.endpoint}") String endpoint,
                            @Value("${craftpilot.minio.access-key}") String accessKey,
                            @Value("${craftpilot.minio.secret-key}") String secretKey) {
        var client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        client.setTimeout(1000, 2000, 2000);
        return client;
    }

    @Bean
    HealthIndicator dbHealthIndicator(JdbcClient jdbc) {
        return new CachedHealth(() -> jdbc.sql("SELECT 1").query(Integer.class).single() == 1);
    }

    @Bean
    HealthIndicator redisHealthIndicator(RedisConnectionFactory factory) {
        return new CachedHealth(() -> {
            try (var connection = factory.getConnection()) {
                return "PONG".equals(connection.ping());
            }
        });
    }

    @Bean
    HealthIndicator minioHealthIndicator(MinioClient client, @Value("${craftpilot.minio.bucket}") String bucket) {
        return new CachedHealth(() -> client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()));
    }

    @FunctionalInterface
    private interface Probe {
        boolean ready() throws Exception;
    }

    private static final class CachedHealth implements HealthIndicator {
        private static final long TTL = Duration.ofSeconds(5).toNanos();
        private final Probe probe;
        private Health cached;
        private long checkedAt;

        private CachedHealth(Probe probe) {
            this.probe = probe;
        }

        @Override
        public synchronized Health health() {
            if (cached == null || System.nanoTime() - checkedAt >= TTL) {
                try {
                    cached = probe.ready() ? Health.up().build() : Health.down().build();
                } catch (Exception failure) {
                    cached = Health.down().build();
                }
                checkedAt = System.nanoTime();
            }
            return cached;
        }
    }
}
