# R5 fault injection results

Offline stubbed models + Testcontainers. Each scenario records injection point and outcome.

| Scenario | Result |
|---|---|
| `bootstrap` | PASS |
| `lease_reclaim_claimed` | 1 |
| `lease_epoch_before` | 1 |
| `lease_epoch_after` | 2 |
| `lease_final_status` | WAITING_APPROVAL |
| `scenario_lease_expiry` | PASS |
| `join_late_reject` | Cancelled run rejects join results |
| `join_duplicate_before` | 1 |
| `scenario_join_duplicate` | PASS |
| `cancel_final_status` | CANCELLED |
| `scenario_cancel_late_approval` | PASS |
| `hitl_concurrent_2xx` | 2 |
| `hitl_approved_rows` | 1 |
| `scenario_hitl_concurrent` | PASS |
| `mcp_disconnect_run_status` | WAITING_APPROVAL |
| `mcp_failed_invocations` | 1 |
| `scenario_mcp_disconnect` | PASS |
| `budget_status` | FAILED |
| `budget_reason` | SUPERVISOR_ExecutionException |
| `scenario_budget_exhaustion` | PASS |
| `seq_stress_events` | 40 |
| `seq_stress_distinct` | 40 |
| `seq_stress_errors` | 0 |
| `scenario_runtime_event_seq_race` | PASS |

**Mode:** automated fault injection (no paid model).
