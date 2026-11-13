package com.craftpilot.runtime.api;

import static com.craftpilot.runtime.RuntimeDtos.*;

import com.craftpilot.runtime.decision.MerchantDecisionService;
import com.craftpilot.runtime.hitl.ApprovalService;
import com.craftpilot.runtime.memory.MemoryService;
import com.craftpilot.runtime.ticket.TicketService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RuntimeController {
    private final TicketService tickets;
    private final RuntimeQueryService queries;
    private final ApprovalService approvals;
    private final MerchantDecisionService decisions;
    private final MemoryService memory;

    public RuntimeController(TicketService tickets, RuntimeQueryService queries, ApprovalService approvals,
                             MerchantDecisionService decisions, MemoryService memory) {
        this.tickets = tickets;
        this.queries = queries;
        this.approvals = approvals;
        this.decisions = decisions;
        this.memory = memory;
    }

    @PostMapping("/merchants/{merchantId}/surveys/{surveyId}/tickets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    TicketView createTicket(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID surveyId,
                            @Valid @RequestBody CreateTicketRequest request,
                            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey) {
        return tickets.createManual(actor(principal), merchantId, surveyId, request, idempotencyKey);
    }

    @GetMapping("/merchants/{merchantId}/tickets/{ticketId}")
    TicketView getTicket(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID ticketId) {
        return tickets.get(actor(principal), merchantId, ticketId);
    }

    @PostMapping("/merchants/{merchantId}/tickets/{ticketId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    TicketView cancelTicket(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID ticketId,
                            @Valid @RequestBody CancelTicketRequest request,
                            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey) {
        return tickets.cancel(actor(principal), merchantId, ticketId, request, idempotencyKey);
    }

    @GetMapping("/merchants/{merchantId}/runtime/runs/{runId}")
    RunView getRun(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID runId) {
        return queries.getRun(actor(principal), merchantId, runId);
    }

    @GetMapping("/merchants/{merchantId}/runtime/runs/{runId}/tasks")
    List<TaskView> listTasks(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID runId) {
        return queries.listTasks(actor(principal), merchantId, runId);
    }

    @GetMapping("/merchants/{merchantId}/runtime/runs/{runId}/events")
    List<EventView> listEvents(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID runId,
                               @RequestParam(name = "afterSeq", defaultValue = "0") long afterSeq) {
        return queries.listEvents(actor(principal), merchantId, runId, afterSeq);
    }

    @GetMapping("/merchants/{merchantId}/runtime/runs/{runId}/model-calls")
    List<ModelCallView> listModelCalls(Principal principal, @PathVariable UUID merchantId,
                                       @PathVariable UUID runId) {
        return queries.listModelCalls(actor(principal), merchantId, runId);
    }

    @GetMapping("/merchants/{merchantId}/runtime/runs/{runId}/tool-calls")
    List<ToolCallView> listToolCalls(Principal principal, @PathVariable UUID merchantId,
                                     @PathVariable UUID runId) {
        return queries.listToolCalls(actor(principal), merchantId, runId);
    }

    @GetMapping("/merchants/{merchantId}/runtime/runs/{runId}/budget")
    BudgetView getBudget(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID runId) {
        return queries.getBudget(actor(principal), merchantId, runId);
    }

    @PostMapping("/merchants/{merchantId}/runtime/runs/{runId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RunView cancelRun(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID runId,
                      @Valid @RequestBody CancelRunRequest request) {
        return queries.cancelRun(actor(principal), merchantId, runId, request);
    }

    @GetMapping("/merchants/{merchantId}/runtime/approvals/{approvalId}")
    ApprovalView getApproval(Principal principal, @PathVariable UUID merchantId,
                             @PathVariable UUID approvalId) {
        var view = approvals.get(merchantId, approvalId);
        return toApprovalView(view);
    }

    @PostMapping("/merchants/{merchantId}/runtime/approvals/{approvalId}/decide")
    ApprovalView decideApproval(Principal principal, @PathVariable UUID merchantId,
                                @PathVariable UUID approvalId,
                                @Valid @RequestBody ApprovalDecisionRequest request) {
        var view = approvals.decide(actor(principal), merchantId, approvalId, request.approve(),
                request.reason(), request.expectedVersion());
        return toApprovalView(view);
    }

    @PostMapping("/merchants/{merchantId}/runtime/approvals/{approvalId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    MapId resumeApproval(Principal principal, @PathVariable UUID merchantId, @PathVariable UUID approvalId,
                         @Valid @RequestBody ResumeApprovalRequest request) {
        UUID resumeId = approvals.consumeAndResume(actor(principal), merchantId, approvalId, request.commandKey());
        return new MapId(resumeId);
    }

    @PostMapping("/merchants/{merchantId}/runtime/decisions/{decisionId}/decide")
    MerchantDecisionView decideMerchant(Principal principal, @PathVariable UUID merchantId,
                                        @PathVariable UUID decisionId,
                                        @Valid @RequestBody MerchantDecisionRequest request) {
        var view = decisions.decide(actor(principal), merchantId, decisionId, request.choice(),
                request.note(), request.expectedVersion());
        return new MerchantDecisionView(view.id(), view.status(), view.recommendation(), view.reportHash(),
                view.version());
    }

    @PostMapping("/merchants/{merchantId}/runtime/memory/proposals/{proposalId}/publish")
    MemoryEntryView publishMemory(Principal principal, @PathVariable UUID merchantId,
                                  @PathVariable UUID proposalId,
                                  @RequestBody(required = false) MemoryPublishRequest request) {
        UUID approvalId = request == null ? null : request.approvalId();
        var view = memory.publish(actor(principal), merchantId, proposalId, approvalId);
        return new MemoryEntryView(view.id(), view.status(), view.version(), view.summary());
    }

    @PostMapping("/merchants/{merchantId}/runtime/memory/entries/{entryId}/revoke")
    MemoryEntryView revokeMemory(Principal principal, @PathVariable UUID merchantId,
                                 @PathVariable UUID entryId,
                                 @RequestParam long expectedVersion) {
        var view = memory.revoke(actor(principal), merchantId, entryId, expectedVersion);
        return new MemoryEntryView(view.id(), view.status(), view.version(), view.summary());
    }

    @GetMapping("/merchants/{merchantId}/runtime/memory")
    List<MemoryEntryView> searchMemory(Principal principal, @PathVariable UUID merchantId,
                                       @RequestParam String scopeType, @RequestParam String scopeKey) {
        return memory.searchActive(actor(principal), merchantId, scopeType, scopeKey).stream()
                .map(v -> new MemoryEntryView(v.id(), v.status(), v.version(), v.summary()))
                .toList();
    }

    private ApprovalView toApprovalView(ApprovalService.ApprovalView view) {
        Instant expires = view.expiresAt() == null ? null : view.expiresAt().toInstant();
        return new ApprovalView(view.id(), view.status(), view.kind(), view.operationDigest(),
                view.version(), expires);
    }

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    public record MapId(UUID id) {}
}
