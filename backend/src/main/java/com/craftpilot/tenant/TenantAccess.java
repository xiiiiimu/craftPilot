package com.craftpilot.tenant;

import com.craftpilot.common.ApiFailure;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class TenantAccess {
    private final JdbcClient jdbc;

    public TenantAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void requireMember(UUID actor, UUID merchant) {
        if (!isActiveMember(actor, merchant)) {
            throw ApiFailure.notFound();
        }
    }

    public void requireOwner(UUID actor, UUID merchant) {
        var role = activeRole(actor, merchant).orElseThrow(ApiFailure::notFound);
        if (!"OWNER".equals(role)) {
            throw ApiFailure.forbidden();
        }
    }

    public boolean isActiveMember(UUID actor, UUID merchant) {
        return activeRole(actor, merchant).isPresent();
    }

    public java.util.Optional<String> activeRole(UUID actor, UUID merchant) {
        return jdbc.sql("""
                SELECT mm.role_code
                FROM merchant_member mm
                JOIN merchant m ON m.id = mm.merchant_id AND m.enabled
                JOIN principal p ON p.id = mm.principal_id AND p.enabled
                WHERE mm.merchant_id = :merchant AND mm.principal_id = :actor AND mm.revoked_at IS NULL
                """).param("merchant", merchant).param("actor", actor).query(String.class).optional();
    }

    public void requireGroupMember(UUID actor, UUID merchant, UUID group) {
        if (!groupRole(actor, merchant, group).isPresent()) {
            throw ApiFailure.notFound();
        }
    }

    public void requireGroupAdminOrOwner(UUID actor, UUID merchant, UUID group) {
        var merchantRole = activeRole(actor, merchant).orElseThrow(ApiFailure::notFound);
        if ("OWNER".equals(merchantRole)) {
            return;
        }
        var role = groupRole(actor, merchant, group).orElseThrow(ApiFailure::notFound);
        if (!"ADMIN".equals(role)) {
            throw ApiFailure.forbidden();
        }
    }

    public java.util.Optional<String> groupRole(UUID actor, UUID merchant, UUID group) {
        return jdbc.sql("""
                SELECT gm.role_code
                FROM group_member gm
                JOIN craft_group g ON g.merchant_id = gm.merchant_id AND g.id = gm.group_id
                JOIN merchant_member mm ON mm.merchant_id = gm.merchant_id AND mm.principal_id = gm.principal_id
                  AND mm.revoked_at IS NULL
                JOIN principal p ON p.id = gm.principal_id AND p.enabled
                JOIN merchant m ON m.id = gm.merchant_id AND m.enabled
                WHERE gm.merchant_id = :merchant AND gm.group_id = :group AND gm.principal_id = :actor
                  AND gm.revoked_at IS NULL
                """).param("merchant", merchant).param("group", group).param("actor", actor)
                .query(String.class).optional();
    }

    public void lockMerchant(UUID merchant) {
        jdbc.sql("SELECT id FROM merchant WHERE id = :merchant AND enabled FOR UPDATE")
                .param("merchant", merchant).query(UUID.class).optional().orElseThrow(ApiFailure::notFound);
    }

    public void lockGroup(UUID merchant, UUID group) {
        jdbc.sql("""
                SELECT id FROM craft_group WHERE merchant_id = :merchant AND id = :group FOR UPDATE
                """).param("merchant", merchant).param("group", group)
                .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);
    }
}
