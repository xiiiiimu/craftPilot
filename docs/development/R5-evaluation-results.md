# R5 evaluation harness results

Distinguishes `MODEL_AUTONOMOUS` vs `RUNTIME_CONTROLLED_SEED` tool provenance.

| Scenario | Result |
|---|---|
| `bootstrap` | OK |
| `autonomous_tool_events` | 2 |
| `scenario_autonomous` | PASS |
| `controlled_seed_events` | 2 |
| `autonomous_on_skip_scenario` | 0 |
| `scenario_skip_seed` | PASS |
| `wrong_tool_status` | FAILED |
| `scenario_wrong_tool` | PASS |
| `fabricated_status` | FAILED |
| `fabricated_reason` | JOIN_FAILED |
| `scenario_fabricated_evidence` | PASS |
| `mcp_injection_invocations` | 1 |
| `mcp_injection_budget_tamper_events` | 0 |
| `scenario_mcp_injection` | PASS |
| `scenario_mcp_ssrf` | PASS |

**Mode:** offline stubbed models + real local MCP protocol.
