package com.craftpilot.catalog;

import static com.craftpilot.catalog.CatalogDtos.AssetView;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.tenant.TenantAccess;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AssetService {
    private static final long MAX_BYTES = 5 * 1024 * 1024;

    private final JdbcClient jdbc;
    private final TenantAccess tenant;
    private final MinioClient minio;
    private final TransactionTemplate tx;
    private final String bucket;

    public AssetService(JdbcClient jdbc, TenantAccess tenant, MinioClient minio, TransactionTemplate tx,
                        @Value("${craftpilot.minio.bucket}") String bucket) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.minio = minio;
        this.tx = tx;
        this.bucket = bucket;
    }

    public AssetView upload(UUID actor, UUID merchant, String kind, String contentType, byte[] bytes) {
        tenant.requireMember(actor, merchant);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw ApiFailure.invalid("Asset content size is out of allowed range");
        }
        if (contentType == null || contentType.isBlank() || contentType.length() > 120) {
            throw ApiFailure.invalid("Invalid content type");
        }
        if (kind == null || kind.isBlank() || kind.length() > 40) {
            throw ApiFailure.invalid("Invalid asset kind");
        }
        UUID id = UUID.randomUUID();
        String objectKey = "merchants/" + merchant + "/assets/" + id;
        String sha = sha256(bytes);

        tx.executeWithoutResult(status -> {
            tenant.requireMember(actor, merchant);
            jdbc.sql("""
                    INSERT INTO asset (merchant_id, id, owner_principal_id, kind, content_type, object_key,
                                       content_sha256, byte_size, status)
                    VALUES (:merchant, :id, :owner, :kind, :contentType, :objectKey, :sha, :size, 'STAGED')
                    """).param("merchant", merchant).param("id", id).param("owner", actor).param("kind", kind)
                    .param("contentType", contentType).param("objectKey", objectKey).param("sha", sha)
                    .param("size", bytes.length).update();
        });

        try {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L)
                    .contentType(contentType).build());
            var stat = minio.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            if (stat.size() != bytes.length) {
                throw new IllegalStateException("uploaded size mismatch");
            }
        } catch (Exception ex) {
            tx.executeWithoutResult(status -> jdbc.sql("""
                    UPDATE asset SET status = 'FAILED', failure_reason = 'OBJECT_STORE_UPLOAD_FAILED',
                                     updated_at = clock_timestamp()
                    WHERE merchant_id = :merchant AND id = :id AND status = 'STAGED'
                    """).param("merchant", merchant).param("id", id).update());
            throw ApiFailure.unavailable("ASSET_STORE_UNAVAILABLE", "Object store upload failed");
        }

        return tx.execute(status -> {
            int updated = jdbc.sql("""
                    UPDATE asset SET status = 'AVAILABLE', object_version = :version, updated_at = clock_timestamp()
                    WHERE merchant_id = :merchant AND id = :id AND status = 'STAGED'
                    """).param("merchant", merchant).param("id", id)
                    .param("version", "1").update();
            if (updated != 1) {
                throw ApiFailure.conflict("ASSET_STATE_CONFLICT", "Asset could not be published");
            }
            return get(actor, merchant, id);
        });
    }

    public AssetView get(UUID actor, UUID merchant, UUID assetId) {
        tenant.requireMember(actor, merchant);
        return jdbc.sql("""
                SELECT id, kind, content_type, status, byte_size, content_sha256, created_at
                FROM asset WHERE merchant_id = :merchant AND id = :id
                """).param("merchant", merchant).param("id", assetId)
                .query((rs, row) -> new AssetView(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("content_type"),
                        rs.getString("status"),
                        rs.getObject("byte_size") == null ? null : rs.getLong("byte_size"),
                        rs.getString("content_sha256"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(ApiFailure::notFound);
    }

    public byte[] content(UUID actor, UUID merchant, UUID assetId) {
        var asset = get(actor, merchant, assetId);
        if (!"AVAILABLE".equals(asset.status())) {
            throw ApiFailure.conflict("ASSET_NOT_AVAILABLE", "Asset content is not available");
        }
        String objectKey = jdbc.sql("""
                SELECT object_key FROM asset WHERE merchant_id = :merchant AND id = :id AND status = 'AVAILABLE'
                """).param("merchant", merchant).param("id", assetId).query(String.class)
                .optional().orElseThrow(ApiFailure::notFound);
        try (InputStream in = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return in.readAllBytes();
        } catch (Exception ex) {
            throw ApiFailure.unavailable("ASSET_STORE_UNAVAILABLE", "Object store read failed");
        }
    }

    @Transactional(readOnly = true)
    public void requireAvailableAssets(UUID merchant, java.util.Collection<UUID> assetIds) {
        for (UUID assetId : assetIds) {
            String status = jdbc.sql("""
                    SELECT status FROM asset WHERE merchant_id = :merchant AND id = :id
                    """).param("merchant", merchant).param("id", assetId).query(String.class)
                    .optional().orElseThrow(ApiFailure::notFound);
            if (!"AVAILABLE".equals(status)) {
                throw ApiFailure.conflict("ASSET_NOT_AVAILABLE", "Referenced asset is not available");
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
