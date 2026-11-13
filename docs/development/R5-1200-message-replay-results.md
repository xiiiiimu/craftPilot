# R5 1200-message replay results

Deterministic corpus seed `20251113`. Messages posted through real Group Chat APIs.

| Metric | Value |
|---|---|
| `merchants` | 3 |
| `groups` | 3 |
| `seed` | 20251113 |
| `target_messages` | 1200 |
| `posted_attempts` | 1200 |
| `accepted_http_2xx` | 1175 |
| `http_status_histogram` | 201=1175,409=25 |
| `duplicate_corpus_flags_accepted_2xx` | 60 |
| `expected_rejects_409_or_403` | 25 |
| `expected_rejects_409` | 25 |
| `expected_rejects_403` | 0 |
| `expected_reject_reason_codes` | IDEMPOTENCY_CONFLICT=25 |
| `reject_samples` | 409 IDEMPOTENCY_CONFLICT login=r5-m1-u2 clientMsgId=3826c89a-e5e9-349d-936e-c7f4018ca5c4 dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-m0-u0 clientMsgId=4a824f2e-6482-350e-b039-dec6d6c165a5 dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-m1-u2 clientMsgId=616b23cb-7b72-3ebc-95d5-26390f928f7b dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-m1-u0 clientMsgId=4a085fed-f84a-3399-8bfa-ad73839efaad dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-m2-u0 clientMsgId=b5769655-6c4b-3806-a65d-dffabb9b62f1 dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-owner-0 clientMsgId=e81e53aa-8f17-3390-83d6-10d1fd35c469 dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-m0-u0 clientMsgId=ea19c462-0417-3b0a-8712-968f0b0aa729 dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-m2-u1 clientMsgId=a3e88fdd-cf67-3f90-8558-2c4dbe040edb dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-m1-u1 clientMsgId=89dbf3ee-90dc-3ad6-ae55-206705393aeb dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-owner-2 clientMsgId=ac12cd9c-5ecc-3c95-a046-fa08a93345d8 dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-m0-u2 clientMsgId=c00b90ff-118e-3533-812d-9ba531eebd58 dupCorpus=true | 409 IDEMPOTENCY_CONFLICT login=r5-m1-u0 clientMsgId=03c89b41-e6f2-3e35-8d37-a9c7f089082f dupCorpus=true |
| `unexpected_rejects` | 0 |
| `transport_errors` | 0 |
| `non_accepted_total` | 25 |
| `reject_classification` | expected=idempotency_conflict(409 same sender+clientMsgId different payload) or card_forbidden(403 non-admin card); unexpected must be 0; accepted 2xx includes true idempotent retries (same payload) |
| `db_messages` | 1175 |
| `unique_client_msg_ids` | 1115 |
| `unique_sender_client_keys` | 1175 |
| `outbox_message_persisted` | 1175 |
| `evidence_refs` | 168 |
| `feedback_issues` | 12 |
| `survey_votes` | 11 |
| `vote_attempts` | 30 |
| `elapsed_ms` | 541437 |
| `throughput_msg_per_sec` | 2.17 |
| `cross_tenant_denied` | true |
| `idempotent_same_payload_rows` | 1 |
| `idempotent_conflict_status` | 409 |
| `tickets_created` | 3 |
| `agent_runs_queued` | 3 |
| `outbox_grew_on_tickets` | true |
| `outcome` | PASSED |

### Rejection taxonomy

- **Accepted (2xx):** new messages and idempotent replays with identical payload.
- **Expected reject (409):** corpus `duplicateOfPrior` reused `clientMsgId` for the same sender with a *different* text/card → `ApiFailure.idempotencyConflict`.
- **Expected reject (403):** non-admin posting a sample card (guarded in corpus by stripping cards for members; residual counted if any).
- **Unexpected:** any other 4xx/5xx or transport exception — must be zero.

**Mode:** offline automated (no paid model).
