package com.craftpilot.groupchat;

import static com.craftpilot.groupchat.GroupDtos.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GroupController {
    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @PostMapping("/merchants/{merchantId}/groups")
    @ResponseStatus(HttpStatus.CREATED)
    GroupView create(Principal principal, @PathVariable UUID merchantId,
                     @Valid @RequestBody CreateGroupRequest request) {
        return groups.createGroup(actor(principal), merchantId, request);
    }

    @GetMapping("/merchants/{merchantId}/groups/{groupId}")
    GroupView get(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID groupId) {
        return groups.getGroup(actor(principal), merchantId, groupId);
    }

    @PutMapping("/merchants/{merchantId}/groups/{groupId}/members/{memberId}")
    GroupMemberView upsertMember(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID groupId,
                                 @PathVariable UUID memberId, @Valid @RequestBody UpsertMemberRequest request) {
        return groups.upsertMember(actor(principal), merchantId, groupId, memberId, request);
    }

    @PostMapping("/merchants/{merchantId}/groups/{groupId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    MessageView postMessage(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID groupId,
                            @Valid @RequestBody PostMessageRequest request) {
        return groups.postMessage(actor(principal), merchantId, groupId, request);
    }

    @GetMapping("/merchants/{merchantId}/groups/{groupId}/messages")
    MessagePage listMessages(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID groupId,
                             @RequestParam(defaultValue = "0") @Min(0) long afterSeq,
                             @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return groups.listMessages(actor(principal), merchantId, groupId, afterSeq, limit);
    }

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
