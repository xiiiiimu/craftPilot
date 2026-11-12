package com.craftpilot.catalog;

import static com.craftpilot.catalog.CatalogDtos.*;

import com.craftpilot.common.ApiFailure;
import com.craftpilot.tenant.TenantAccess;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final JdbcClient jdbc;
    private final TenantAccess tenant;
    private final AssetService assets;

    public CatalogService(JdbcClient jdbc, TenantAccess tenant, AssetService assets) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.assets = assets;
    }

    @Transactional
    public CampaignView createCampaign(UUID actor, UUID merchant, CreateCampaignRequest request) {
        tenant.requireOwner(actor, merchant);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO launch_campaign (merchant_id, id, name, description, created_by)
                VALUES (:merchant, :id, :name, :description, :actor)
                """).param("merchant", merchant).param("id", id).param("name", request.name().trim())
                .param("description", blankToNull(request.description())).param("actor", actor).update();
        return getCampaign(actor, merchant, id);
    }

    public CampaignView getCampaign(UUID actor, UUID merchant, UUID campaignId) {
        tenant.requireMember(actor, merchant);
        return jdbc.sql("""
                SELECT id, name, description, created_at FROM launch_campaign
                WHERE merchant_id = :merchant AND id = :id
                """).param("merchant", merchant).param("id", campaignId)
                .query((rs, row) -> new CampaignView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .optional().orElseThrow(ApiFailure::notFound);
    }

    public List<CampaignView> listCampaigns(UUID actor, UUID merchant) {
        tenant.requireMember(actor, merchant);
        return jdbc.sql("""
                SELECT id, name, description, created_at FROM launch_campaign
                WHERE merchant_id = :merchant ORDER BY created_at, id
                """).param("merchant", merchant)
                .query((rs, row) -> new CampaignView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
    }

    @Transactional
    public StyleView createStyle(UUID actor, UUID merchant, UUID campaignId, CreateStyleRequest request) {
        tenant.requireOwner(actor, merchant);
        getCampaign(actor, merchant, campaignId);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO candidate_style (merchant_id, campaign_id, id, name, category)
                VALUES (:merchant, :campaign, :id, :name, :category)
                """).param("merchant", merchant).param("campaign", campaignId).param("id", id)
                .param("name", request.name().trim()).param("category", request.category().trim()).update();
        return getStyle(actor, merchant, id);
    }

    public StyleView getStyle(UUID actor, UUID merchant, UUID styleId) {
        tenant.requireMember(actor, merchant);
        return jdbc.sql("""
                SELECT id, campaign_id, name, category FROM candidate_style
                WHERE merchant_id = :merchant AND id = :id
                """).param("merchant", merchant).param("id", styleId)
                .query((rs, row) -> new StyleView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("campaign_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("category")))
                .optional().orElseThrow(ApiFailure::notFound);
    }

    @Transactional
    public RevisionView createRevision(UUID actor, UUID merchant, UUID styleId, CreateRevisionRequest request) {
        tenant.requireOwner(actor, merchant);
        jdbc.sql("""
                SELECT id FROM candidate_style WHERE merchant_id = :merchant AND id = :style FOR UPDATE
                """).param("merchant", merchant).param("style", styleId)
                .query(UUID.class).optional().orElseThrow(ApiFailure::notFound);
        tenant.requireOwner(actor, merchant);

        Long currentNo = jdbc.sql("""
                SELECT next_revision_no FROM candidate_style WHERE merchant_id = :merchant AND id = :style
                """).param("merchant", merchant).param("style", styleId).query(Long.class).single();
        long nextNo = currentNo + 1;
        if (nextNo == 1) {
            if (request.parentRevisionId() != null) {
                throw ApiFailure.invalid("First revision cannot have a parent");
            }
        } else {
            if (request.parentRevisionId() == null) {
                throw ApiFailure.invalid("Parent revision is required");
            }
            Long parentNo = jdbc.sql("""
                    SELECT revision_no FROM sample_revision
                    WHERE merchant_id = :merchant AND style_id = :style AND id = :parent
                    """).param("merchant", merchant).param("style", styleId)
                    .param("parent", request.parentRevisionId()).query(Long.class)
                    .optional().orElseThrow(() -> ApiFailure.conflict("PARENT_REVISION_MISMATCH",
                            "Parent revision must belong to the same style"));
            if (parentNo != currentNo) {
                throw ApiFailure.conflict("PARENT_REVISION_MISMATCH", "Parent revision must be the current head");
            }
        }

        var assetIds = request.assetIds().stream().distinct().toList();
        assets.requireAvailableAssets(merchant, assetIds);
        validateBom(request.bomItems());

        UUID revisionId = UUID.randomUUID();
        String snapshotHash = snapshotHash(request);
        try {
            jdbc.sql("""
                    INSERT INTO sample_revision (merchant_id, style_id, id, revision_no, parent_revision_id,
                                                 notes, snapshot_hash, created_by)
                    VALUES (:merchant, :style, :id, :no, :parent, :notes, :hash, :actor)
                    """).param("merchant", merchant).param("style", styleId).param("id", revisionId)
                    .param("no", nextNo).param("parent", request.parentRevisionId())
                    .param("notes", blankToNull(request.notes())).param("hash", snapshotHash)
                    .param("actor", actor).update();
        } catch (DataIntegrityViolationException ex) {
            throw ApiFailure.conflict("REVISION_NO_CONFLICT", "Revision number already exists");
        }

        for (UUID assetId : assetIds) {
            jdbc.sql("""
                    INSERT INTO revision_asset (merchant_id, revision_id, asset_id)
                    VALUES (:merchant, :revision, :asset)
                    """).param("merchant", merchant).param("revision", revisionId).param("asset", assetId).update();
        }
        for (BomItemRequest item : request.bomItems()) {
            jdbc.sql("""
                    INSERT INTO bom_item (merchant_id, revision_id, line_no, material_code, material_name,
                                          quantity, unit, unit_cost, currency, cost_gap)
                    VALUES (:merchant, :revision, :lineNo, :code, :name, :qty, :unit, :cost, :currency, :gap)
                    """).param("merchant", merchant).param("revision", revisionId).param("lineNo", item.lineNo())
                    .param("code", item.materialCode().trim()).param("name", item.materialName().trim())
                    .param("qty", item.quantity()).param("unit", item.unit().trim())
                    .param("cost", item.unitCost()).param("currency", item.currency())
                    .param("gap", item.costGap()).update();
        }
        jdbc.sql("""
                UPDATE candidate_style SET next_revision_no = :no, updated_at = clock_timestamp()
                WHERE merchant_id = :merchant AND id = :style AND next_revision_no = :prev
                """).param("merchant", merchant).param("style", styleId).param("no", nextNo)
                .param("prev", currentNo).update();
        return getRevision(actor, merchant, revisionId);
    }

    public RevisionView getRevision(UUID actor, UUID merchant, UUID revisionId) {
        tenant.requireMember(actor, merchant);
        var revision = jdbc.sql("""
                SELECT id, style_id, revision_no, parent_revision_id, notes, snapshot_hash, created_at
                FROM sample_revision WHERE merchant_id = :merchant AND id = :id
                """).param("merchant", merchant).param("id", revisionId)
                .query((rs, row) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getObject("style_id", UUID.class),
                        rs.getLong("revision_no"),
                        rs.getObject("parent_revision_id", UUID.class),
                        rs.getString("notes"),
                        rs.getString("snapshot_hash"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()
                }).optional().orElseThrow(ApiFailure::notFound);
        var assetIds = jdbc.sql("""
                SELECT asset_id FROM revision_asset WHERE merchant_id = :merchant AND revision_id = :id
                ORDER BY asset_id
                """).param("merchant", merchant).param("id", revisionId).query(UUID.class).list();
        var bom = jdbc.sql("""
                SELECT line_no, material_code, material_name, quantity, unit, unit_cost, currency, cost_gap
                FROM bom_item WHERE merchant_id = :merchant AND revision_id = :id ORDER BY line_no
                """).param("merchant", merchant).param("id", revisionId)
                .query((rs, row) -> new BomItemView(
                        rs.getInt("line_no"),
                        rs.getString("material_code"),
                        rs.getString("material_name"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("unit"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getString("currency"),
                        rs.getBoolean("cost_gap")))
                .list();
        return new RevisionView((UUID) revision[0], (UUID) revision[1], (Long) revision[2],
                (UUID) revision[3], (String) revision[4], (String) revision[5],
                (java.time.Instant) revision[6], assetIds, bom);
    }

    private void validateBom(List<BomItemRequest> items) {
        var lines = items.stream().map(BomItemRequest::lineNo).sorted().toList();
        if (lines.stream().distinct().count() != lines.size()) {
            throw ApiFailure.invalid("BOM line numbers must be unique");
        }
        for (BomItemRequest item : items) {
            boolean hasCost = item.unitCost() != null;
            boolean hasCurrency = item.currency() != null && !item.currency().isBlank();
            if (hasCost != hasCurrency) {
                throw ApiFailure.invalid("BOM cost and currency must be provided together");
            }
            if (item.costGap() && hasCost) {
                throw ApiFailure.invalid("BOM cost gap cannot include a unit cost");
            }
        }
    }

    private static String snapshotHash(CreateRevisionRequest request) {
        var bom = new ArrayList<>(request.bomItems());
        bom.sort(Comparator.comparingInt(BomItemRequest::lineNo));
        StringBuilder sb = new StringBuilder();
        sb.append(request.parentRevisionId()).append('|').append(request.notes()).append('|');
        request.assetIds().stream().sorted().forEach(id -> sb.append(id).append(','));
        sb.append('|');
        for (BomItemRequest item : bom) {
            sb.append(item.lineNo()).append(':').append(item.materialCode()).append(':')
                    .append(item.quantity()).append(':').append(item.unit()).append(';');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
