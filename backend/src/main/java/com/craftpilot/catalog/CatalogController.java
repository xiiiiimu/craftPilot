package com.craftpilot.catalog;

import static com.craftpilot.catalog.CatalogDtos.*;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService catalog;
    private final AssetService assets;

    public CatalogController(CatalogService catalog, AssetService assets) {
        this.catalog = catalog;
        this.assets = assets;
    }

    @PostMapping("/merchants/{merchantId}/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    CampaignView createCampaign(Principal principal, @PathVariable UUID merchantId,
                                @Valid @RequestBody CreateCampaignRequest request) {
        return catalog.createCampaign(actor(principal), merchantId, request);
    }

    @GetMapping("/merchants/{merchantId}/campaigns")
    List<CampaignView> listCampaigns(Principal principal, @PathVariable UUID merchantId) {
        return catalog.listCampaigns(actor(principal), merchantId);
    }

    @GetMapping("/merchants/{merchantId}/campaigns/{campaignId}")
    CampaignView getCampaign(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID campaignId) {
        return catalog.getCampaign(actor(principal), merchantId, campaignId);
    }

    @PostMapping("/merchants/{merchantId}/campaigns/{campaignId}/styles")
    @ResponseStatus(HttpStatus.CREATED)
    StyleView createStyle(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID campaignId,
                          @Valid @RequestBody CreateStyleRequest request) {
        return catalog.createStyle(actor(principal), merchantId, campaignId, request);
    }

    @GetMapping("/merchants/{merchantId}/styles/{styleId}")
    StyleView getStyle(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID styleId) {
        return catalog.getStyle(actor(principal), merchantId, styleId);
    }

    @PostMapping("/merchants/{merchantId}/styles/{styleId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    RevisionView createRevision(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID styleId,
                                @Valid @RequestBody CreateRevisionRequest request) {
        return catalog.createRevision(actor(principal), merchantId, styleId, request);
    }

    @GetMapping("/merchants/{merchantId}/revisions/{revisionId}")
    RevisionView getRevision(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID revisionId) {
        return catalog.getRevision(actor(principal), merchantId, revisionId);
    }

    @PostMapping(value = "/merchants/{merchantId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    AssetView uploadAsset(Principal principal, @PathVariable UUID merchantId,
                          @RequestPart("kind") String kind,
                          @RequestPart("file") MultipartFile file) throws Exception {
        return assets.upload(actor(principal), merchantId, kind,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                file.getBytes());
    }

    @GetMapping("/merchants/{merchantId}/assets/{assetId}")
    AssetView getAsset(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID assetId) {
        return assets.get(actor(principal), merchantId, assetId);
    }

    @GetMapping("/merchants/{merchantId}/assets/{assetId}/content")
    ResponseEntity<byte[]> assetContent(Principal principal, @PathVariable UUID merchantId,
                                        @PathVariable UUID assetId) {
        var meta = assets.get(actor(principal), merchantId, assetId);
        byte[] body = assets.content(actor(principal), merchantId, assetId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, meta.contentType())
                .body(body);
    }

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
