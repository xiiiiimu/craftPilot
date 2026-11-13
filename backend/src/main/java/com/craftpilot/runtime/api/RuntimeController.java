package com.craftpilot.runtime.api;

import static com.craftpilot.runtime.RuntimeDtos.*;

import com.craftpilot.runtime.ticket.TicketService;
import java.security.Principal;
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

    public RuntimeController(TicketService tickets, RuntimeQueryService queries) {
        this.tickets = tickets;
        this.queries = queries;
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

    private UUID actor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
