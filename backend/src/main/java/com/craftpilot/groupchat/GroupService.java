package com.craftpilot.groupchat;

import static com.craftpilot.groupchat.GroupDtos.*;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.events.OutboxWriter;
import com.craftpilot.tenant.TenantAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {
    private final JdbcClient jdbc;
    private final TenantAccess tenant;
    private final OutboxWriter outbox;

    public GroupService(JdbcClient jdbc, TenantAccess tenant, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.outbox = outbox;
    }

    @Transactional
    public GroupView createGroup(UUID actor, UUID merchant, CreateGroupRequest request) {
        tenant.requireOwner(actor, merchant);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO craft_group (merchant_id, id, name) VALUES (:merchant, :id, :name)
                """).param("merchant", merchant).param("id", id).param("name", request.name().trim()).update();
        jdbc.sql("""
                INSERT INTO group_member (merchant_id, group_id, principal_id, role_code)
                VALUES (:merchant, :group, :actor, 'ADMIN')
                """).param("merchant", merchant).param("group", id).param("actor", actor).update();
        return getGroup(actor, merchant, id);
    }

    public GroupView getGroup(UUID actor, UUID merchant, UUID groupId) {
        tenant.requireGroupMember(actor, merchant, groupId);
        return jdbc.sql("""
                SELECT id, name, next_message_seq FROM craft_group
                WHERE merchant_id = :merchant AND id = :id
                """).param("merchant", merchant).param("id", groupId)
                .query((rs, row) -> new GroupView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getLong("next_message_seq")))
                .optional().orElseThrow(ApiFailure::notFound);
    }

    @Transactional
    public GroupMemberView upsertMember(UUID actor, UUID merchant, UUID groupId, UUID memberId,
                                        UpsertMemberRequest request) {
        tenant.requireGroupAdminOrOwner(actor, merchant, groupId);
        tenant.requireMember(memberId, merchant);
        String role = request.role().trim().toUpperCase();
        if (!role.equals("MEMBER") && !role.equals("ADMIN")) {
            throw ApiFailure.invalid("Invalid group role");
        }
        if (role.equals("ADMIN") && !"OWNER".equals(tenant.activeRole(actor, merchant).orElse(""))) {
            throw ApiFailure.forbidden();
        }
        tenant.lockGroup(merchant, groupId);
        tenant.requireGroupAdminOrOwner(actor, merchant, groupId);

        var existing = jdbc.sql("""
                SELECT role_code, revoked_at, version FROM group_member
                WHERE merchant_id = :merchant AND group_id = :group AND principal_id = :member
                """).param("merchant", merchant).param("group", groupId).param("member", memberId)
                .query((rs, row) -> Map.of(
                        "role", rs.getString("role_code"),
                        "revoked", String.valueOf(rs.getObject("revoked_at") != null),
                        "version", String.valueOf(rs.getLong("version"))))
                .optional();

        if (existing.isPresent() && request.expectedVersion() != null
                && Long.parseLong(existing.get().get("version")) != request.expectedVersion()) {
            throw ApiFailure.conflict("VERSION_CONFLICT", "Group member version mismatch");
        }

        if (existing.isEmpty()) {
            if (!request.enabled()) {
                throw ApiFailure.invalid("Cannot create a revoked membership");
            }
            jdbc.sql("""
                    INSERT INTO group_member (merchant_id, group_id, principal_id, role_code, revoked_at)
                    VALUES (:merchant, :group, :member, :role, NULL)
                    """).param("merchant", merchant).param("group", groupId).param("member", memberId)
                    .param("role", role).update();
        } else {
            jdbc.sql("""
                    UPDATE group_member
                    SET role_code = :role,
                        revoked_at = CASE WHEN :enabled THEN NULL ELSE clock_timestamp() END,
                        version = version + 1,
                        updated_at = clock_timestamp()
                    WHERE merchant_id = :merchant AND group_id = :group AND principal_id = :member
                    """).param("merchant", merchant).param("group", groupId).param("member", memberId)
                    .param("role", role).param("enabled", request.enabled()).update();
        }
        return jdbc.sql("""
                SELECT principal_id, role_code, revoked_at, version FROM group_member
                WHERE merchant_id = :merchant AND group_id = :group AND principal_id = :member
                """).param("merchant", merchant).param("group", groupId).param("member", memberId)
                .query((rs, row) -> new GroupMemberView(
                        rs.getObject("principal_id", UUID.class),
                        rs.getString("role_code"),
                        rs.getObject("revoked_at") == null,
                        rs.getLong("version")))
                .single();
    }

    @Transactional
    public MessageView postMessage(UUID actor, UUID merchant, UUID groupId, PostMessageRequest request) {
        tenant.requireGroupMember(actor, merchant, groupId);
        tenant.lockGroup(merchant, groupId);
        tenant.requireGroupMember(actor, merchant, groupId);

        String text = request.text() == null ? null : request.text().trim();
        UUID cardRevision = request.card() == null ? null : request.card().revisionId();
        if ((text == null || text.isEmpty()) && cardRevision == null) {
            throw ApiFailure.invalid("Message requires text or a sample card");
        }
        if (cardRevision != null) {
            tenant.requireGroupAdminOrOwner(actor, merchant, groupId);
            jdbc.sql("""
                    SELECT id FROM sample_revision WHERE merchant_id = :merchant AND id = :revision
                    """).param("merchant", merchant).param("revision", cardRevision)
                    .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);
        }
        if (request.replyToId() != null) {
            jdbc.sql("""
                    SELECT id FROM group_message
                    WHERE merchant_id = :merchant AND group_id = :group AND id = :reply
                    """).param("merchant", merchant).param("group", groupId).param("reply", request.replyToId())
                    .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);
        }

        String payloadHash = hashPayload(request.clientMsgId(), text, request.replyToId(), cardRevision,
                request.assetIds());
        var existing = jdbc.sql("""
                SELECT id, sender_id, client_msg_id, group_seq, text_body, reply_to_id, card_revision_id,
                       survey_id, received_at, payload_hash
                FROM group_message
                WHERE merchant_id = :merchant AND group_id = :group AND sender_id = :sender
                  AND client_msg_id = :clientMsg
                """).param("merchant", merchant).param("group", groupId).param("sender", actor)
                .param("clientMsg", request.clientMsgId())
                .query(this::messageRow).optional();
        if (existing.isPresent()) {
            String previousHash = jdbc.sql("""
                    SELECT payload_hash FROM group_message WHERE merchant_id = :merchant AND id = :id
                    """).param("merchant", merchant).param("id", existing.get().id()).query(String.class).single();
            if (!previousHash.equals(payloadHash)) {
                throw ApiFailure.idempotencyConflict();
            }
            return existing.get();
        }

        long nextSeq = jdbc.sql("""
                UPDATE craft_group SET next_message_seq = next_message_seq + 1, updated_at = clock_timestamp()
                WHERE merchant_id = :merchant AND id = :group
                RETURNING next_message_seq
                """).param("merchant", merchant).param("group", groupId).query(Long.class).single();

        UUID surveyId = jdbc.sql("""
                SELECT id FROM survey_session
                WHERE merchant_id = :merchant AND group_id = :group AND status = 'ACTIVE'
                  AND starts_at <= clock_timestamp() AND clock_timestamp() < intake_until
                """).param("merchant", merchant).param("group", groupId).query(UUID.class).optional().orElse(null);

        UUID messageId = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO group_message (merchant_id, group_id, id, sender_id, client_msg_id, group_seq,
                                               payload_hash, text_body, reply_to_id, card_revision_id, survey_id,
                                               received_at)
                    VALUES (:merchant, :group, :id, :sender, :clientMsg, :seq, :hash, :text, :reply, :card,
                            :survey, clock_timestamp())
                    """).param("merchant", merchant).param("group", groupId).param("id", messageId)
                    .param("sender", actor).param("clientMsg", request.clientMsgId()).param("seq", nextSeq)
                    .param("hash", payloadHash).param("text", text).param("reply", request.replyToId())
                    .param("card", cardRevision).param("survey", surveyId).update();
        } catch (DuplicateKeyException ex) {
            return postMessage(actor, merchant, groupId, request);
        }

        if (surveyId != null) {
            jdbc.sql("""
                    INSERT INTO message_survey_link (merchant_id, message_id, survey_id)
                    VALUES (:merchant, :message, :survey)
                    """).param("merchant", merchant).param("message", messageId).param("survey", surveyId).update();
        }
        if (request.assetIds() != null) {
            for (UUID assetId : request.assetIds().stream().distinct().toList()) {
                jdbc.sql("""
                        SELECT id FROM asset WHERE merchant_id = :merchant AND id = :asset AND status = 'AVAILABLE'
                        """).param("merchant", merchant).param("asset", assetId)
                        .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);
                jdbc.sql("""
                        INSERT INTO message_asset (merchant_id, message_id, asset_id)
                        VALUES (:merchant, :message, :asset)
                        """).param("merchant", merchant).param("message", messageId).param("asset", assetId).update();
            }
        }

        MessageView saved = getMessage(merchant, messageId);
        outbox.enqueue(merchant, "group_message", messageId, "MESSAGE_PERSISTED", Map.of(
                "merchantId", merchant,
                "groupId", groupId,
                "messageId", messageId,
                "groupSeq", saved.groupSeq(),
                "senderId", saved.senderId()));
        return saved;
    }

    public MessagePage listMessages(UUID actor, UUID merchant, UUID groupId, long afterSeq, int limit) {
        tenant.requireGroupMember(actor, merchant, groupId);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        var rows = jdbc.sql("""
                SELECT id, sender_id, client_msg_id, group_seq, text_body, reply_to_id, card_revision_id,
                       survey_id, received_at
                FROM group_message
                WHERE merchant_id = :merchant AND group_id = :group AND group_seq > :after
                ORDER BY group_seq ASC
                LIMIT :limit
                """).param("merchant", merchant).param("group", groupId).param("after", afterSeq)
                .param("limit", safeLimit + 1)
                .query(this::messageRow).list();
        boolean hasMore = rows.size() > safeLimit;
        var items = hasMore ? rows.subList(0, safeLimit) : rows;
        Long next = items.isEmpty() ? null : items.get(items.size() - 1).groupSeq();
        return new MessagePage(items, hasMore ? next : (items.isEmpty() ? afterSeq : next));
    }

    private MessageView getMessage(UUID merchant, UUID messageId) {
        return jdbc.sql("""
                SELECT id, sender_id, client_msg_id, group_seq, text_body, reply_to_id, card_revision_id,
                       survey_id, received_at
                FROM group_message WHERE merchant_id = :merchant AND id = :id
                """).param("merchant", merchant).param("id", messageId).query(this::messageRow).single();
    }

    private MessageView messageRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new MessageView(
                rs.getObject("id", UUID.class),
                rs.getObject("sender_id", UUID.class),
                rs.getObject("client_msg_id", UUID.class),
                rs.getLong("group_seq"),
                rs.getString("text_body"),
                rs.getObject("reply_to_id", UUID.class),
                rs.getObject("card_revision_id", UUID.class),
                rs.getObject("survey_id", UUID.class),
                rs.getObject("received_at", java.time.OffsetDateTime.class).toInstant());
    }

    private static String hashPayload(UUID clientMsgId, String text, UUID replyTo, UUID card, List<UUID> assets) {
        StringBuilder sb = new StringBuilder();
        sb.append(clientMsgId).append('|').append(text).append('|').append(replyTo).append('|').append(card).append('|');
        if (assets != null) {
            assets.stream().sorted().forEach(id -> sb.append(id).append(','));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
