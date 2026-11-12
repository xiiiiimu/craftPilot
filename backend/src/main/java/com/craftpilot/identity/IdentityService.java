package com.craftpilot.identity;

import static com.craftpilot.identity.IdentityDtos.*;

import com.craftpilot.common.ApiFailure;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private static final String VISIBLE_MERCHANTS = """
            SELECT m.id, m.name, mm.role_code
            FROM merchant_member mm
            JOIN merchant m ON m.id = mm.merchant_id
            JOIN principal p ON p.id = mm.principal_id
            WHERE mm.principal_id = :actor AND mm.revoked_at IS NULL AND m.enabled AND p.enabled
            """;
    private final JdbcClient jdbc;

    public IdentityService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Me me(UUID actor) {
        return jdbc.sql("SELECT id, login, display_name FROM principal WHERE id = :actor AND enabled")
                .param("actor", actor).query((rs, row) ->
                        new Me(rs.getObject("id", UUID.class), rs.getString("login"), rs.getString("display_name")))
                .optional().orElseThrow(ApiFailure::notFound);
    }

    public Page<MerchantView> merchants(UUID actor, int limit, int offset) {
        var rows = jdbc.sql(VISIBLE_MERCHANTS + " ORDER BY m.id LIMIT :limit OFFSET :offset")
                .param("actor", actor).param("limit", limit + 1).param("offset", offset)
                .query(this::merchantRow).list();
        return Page.from(rows, limit, offset);
    }

    public MerchantView merchant(UUID actor, UUID merchant) {
        return jdbc.sql(VISIBLE_MERCHANTS + " AND m.id = :merchant")
                .param("actor", actor).param("merchant", merchant)
                .query(this::merchantRow).optional().orElseThrow(ApiFailure::notFound);
    }

    public Page<MemberView> members(UUID actor, UUID merchant, int limit, int offset) {
        requireOwner(actor, merchant);
        var rows = jdbc.sql("""
                SELECT mm.principal_id, p.display_name, mm.role_code, mm.revoked_at
                FROM merchant_member mm JOIN principal p ON p.id = mm.principal_id
                JOIN merchant m ON m.id = mm.merchant_id AND m.enabled
                WHERE mm.merchant_id = :merchant AND EXISTS (
                    SELECT 1 FROM merchant_member a JOIN principal ap ON ap.id = a.principal_id
                    WHERE a.merchant_id = mm.merchant_id AND a.principal_id = :actor
                      AND a.revoked_at IS NULL AND a.role_code = 'OWNER' AND ap.enabled
                )
                ORDER BY mm.principal_id LIMIT :limit OFFSET :offset
                """).param("actor", actor).param("merchant", merchant)
                .param("limit", limit + 1).param("offset", offset)
                .query((rs, row) -> {
                    var revoked = rs.getObject("revoked_at", OffsetDateTime.class);
                    return new MemberView(rs.getObject("principal_id", UUID.class),
                            rs.getString("display_name"), Role.valueOf(rs.getString("role_code")),
                            revoked == null ? null : revoked.toInstant());
                }).list();
        return Page.from(rows, limit, offset);
    }

    @Transactional
    public void revokeMember(UUID actor, UUID merchant, UUID target, UUID commandKey) {
        requireOwner(actor, merchant);
        jdbc.sql("SELECT id FROM merchant WHERE id = :merchant FOR UPDATE")
                .param("merchant", merchant).query(UUID.class).single();
        requireOwner(actor, merchant);
        var previous = jdbc.sql("""
                SELECT target_id FROM membership_revoke_command
                WHERE merchant_id = :merchant AND actor_id = :actor AND command_key = :key
                """).param("merchant", merchant).param("actor", actor).param("key", commandKey)
                .query(UUID.class).optional();
        if (previous.isPresent()) {
            if (!previous.get().equals(target)) {
                throw ApiFailure.idempotencyConflict();
            }
            return;
        }
        var role = jdbc.sql("""
                SELECT role_code FROM merchant_member WHERE merchant_id = :merchant AND principal_id = :target
                """).param("merchant", merchant).param("target", target)
                .query(String.class).optional().orElseThrow(ApiFailure::notFound);
        if (Role.OWNER.name().equals(role)) {
            throw ApiFailure.protectedOwner();
        }
        jdbc.sql("""
                UPDATE merchant_member target SET revoked_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE target.merchant_id = :merchant AND target.principal_id = :target
                  AND target.revoked_at IS NULL AND target.role_code = 'MEMBER'
                  AND EXISTS (
                    SELECT 1 FROM merchant_member owner
                    JOIN principal p ON p.id = owner.principal_id AND p.enabled
                    JOIN merchant m ON m.id = owner.merchant_id AND m.enabled
                    WHERE owner.merchant_id = target.merchant_id AND owner.principal_id = :actor
                      AND owner.role_code = 'OWNER' AND owner.revoked_at IS NULL
                  )
                """).param("merchant", merchant).param("target", target).param("actor", actor).update();
        requireOwner(actor, merchant);
        jdbc.sql("""
                INSERT INTO membership_revoke_command (merchant_id, actor_id, command_key, target_id)
                VALUES (:merchant, :actor, :key, :target)
                """).param("merchant", merchant).param("actor", actor).param("key", commandKey)
                .param("target", target).update();
    }

    private void requireOwner(UUID actor, UUID merchant) {
        if (merchant(actor, merchant).role() != Role.OWNER) {
            throw ApiFailure.forbidden();
        }
    }

    private MerchantView merchantRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new MerchantView(rs.getObject("id", UUID.class), rs.getString("name"),
                Role.valueOf(rs.getString("role_code")));
    }
}
