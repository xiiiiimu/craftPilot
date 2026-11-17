# R5 Windows one-click + V1→V2 demo

## Verification status

| Item | Status | Notes |
|---|---|---|
| `r5-one-click.ps1` | **PASS** | Infra TCP healthy (15432/17379/19000); jar packaged; Compose postgres/redis/minio healthy; no `data/` wipe; no `.env` rewrite |
| `r5-demo-v1-v2.ps1 -PrintOnly` | **PASS (preview only)** | Parses and prints API steps; no mutations |
| Interactive V1→V2 on `:18080` | **PARTIAL** | See the November 17 Windows follow-up below for live results and outstanding gates. |
| Full product V1→V2+HITL path | Covered by **`R5ProviderE2ETest`** (isolated Testcontainers), not by this Windows operator demo |

**Do not mark the Windows interactive demo PASS from PrintOnly alone.**

## Purpose

Provide a safe Windows path for R5 acceptance:

1. One-click infra + jar readiness (`scripts/r5-one-click.ps1`)
2. Documented / optional API bootstrap for the V1→V2 demo story (`scripts/r5-demo-v1-v2.ps1`)
3. Clear hand-off to `scripts/backend-up.ps1` and R5 acceptance tests

R5 requirements (see [implementation plan](../implementation-plan.md) M5 and [roadmap](../architecture/implementation-roadmap.md)) call for reproducible one-click startup and a demo of:

**V1 sample → survey → issues/evidence → research report → merchant confirmation → V2 revision → new survey**

with V1 history remaining readable.

## Safety rules

| Rule | Enforcement |
|---|---|
| Do not delete `data/` | `r5-one-click.ps1` never removes or wipes `data/`; Compose volumes stay as bind mounts under `data/` |
| Do not overwrite `.env` | Scripts refuse to create/rewrite `.env`; missing file or empty required keys fail with diagnostics |
| Do not print secrets | `.env` check reports key **present/absent** only |
| Do not auto-start backend | One-click prints next steps; operator runs `backend-up.ps1` |
| Do not auto-approve production actions | Demo script never POSTs `/runtime/approvals/*/decide` or `/runtime/decisions/*/decide` |
| Isolate demo merchants | Demo logins must use prefix `r5-demo-*` |

These scripts wrap existing helpers: `scripts/compose-common.ps1`, `scripts/dev-up.ps1`, `scripts/backend-up.ps1`.

## Prerequisites

1. Windows with Docker Desktop (Linux containers), local `npipe` context.
2. Project `.env` already created from `.env.example` with the three development passwords set (see [docker-setup](docker-setup.md)).
3. Java 21 JDK available when building the jar or starting the backend (`JAVA_HOME` recommended).

Optional for live embedding/vision during the demo: `CRAFTPILOT_OPENAI_API_KEY`, `CRAFTPILOT_QWEN_API_KEY` (and chat key if exercising live chat). Absence is reported; infra still starts.

## Script: `scripts/r5-one-click.ps1`

### What it does

1. Asserts Docker / local Linux engine (`Assert-CraftPilotDocker`).
2. Checks `.env` exists; reports required and optional keys as present/absent.
3. Calls `scripts/dev-up.ps1` (postgres, redis, minio; idempotent `--no-recreate`).
4. If `backend/target/craftpilot-backend-0.1.0-SNAPSHOT.jar` is missing, runs  
   `backend/mvnw.cmd -B -ntp -DskipTests package`.
5. Health-checks postgres / redis / minio via Compose health + TCP to configured host ports.
6. Prints next steps for backend start, V1→V2 demo, and acceptance tests.
7. On failure: prints Compose `ps` / recent logs and exits non-zero.

### What it does not do

- Delete `data/` or stop unrelated Docker projects
- Overwrite `.env`
- Start `java -jar` (that remains `backend-up.ps1`)
- Run Maven tests

### Expected outcomes (when verification is run)

| Check | Expected |
|---|---|
| Docker | Local Linux engine ready |
| `.env` | Required keys present |
| Compose | `postgres`, `redis`, `minio` healthy; TCP open on configured ports (defaults 15432 / 17379 / 19000) |
| Jar | Present under `backend/target/` |
| Exit code | `0` on success |

**Status: PASS** (executed locally; see Verification status).

### Usage

```powershell
Set-Location E:\shopper
.\scripts\r5-one-click.ps1
```

## Script: `scripts/r5-demo-v1-v2.ps1`

### Story (R5)

Matches the reproducible demo described in M5 / T13:

1. Campaign + style + **V1** revision (asset + BOM)
2. Group survey on V1; card-attributed member feedback
3. Issues / feedback-stats / retrieval (and vision when V2 exists)
4. **Manual** HITL approval / merchant decision if runtime raises them
5. **V2** revision with `parentRevisionId` = V1
6. New survey covering V1+V2; further feedback; V1 history remains queryable

### Modes

| Mode | Behavior |
|---|---|
| `-PrintOnly` | Prints `Invoke-RestMethod` / curl sketches; no mutations |
| Interactive / `-Login` | Calls APIs when backend is up; prompts for password if needed |
| Backend down | Interactive execution exits nonzero. Preview remains available with `-PrintOnly`. |
| `-UseProviders` | Opts into retrieval and Qwen-VL requests. Use only after authorizing Provider usage and configuring the backend. |

### Identity isolation

- Bootstrap owner with `CRAFTPILOT_BOOTSTRAP_LOGIN=r5-demo-owner` (or another `r5-demo-*` login) on first `backend-up.ps1`.
- The demo requires a loopback HTTP origin, a matching authenticated `r5-demo-*` login, and exactly one `Local development` merchant with the `OWNER` role. Use a dedicated identity created by the development bootstrap.
- Opening the second survey in the same room requires the first to be `CLOSED`. The script sends `Idempotency-Key`, waits for the ten-minute closing window, then submits a fresh close command to advance the state.

### Manual gates (required)

The script **does not** auto-approve:

- `POST /api/v1/merchants/{m}/runtime/approvals/{id}/decide`
- `POST /api/v1/merchants/{m}/runtime/decisions/{id}/decide`

Operator inspects Ticket / Run / Approval / Decision via GET APIs or UI, then decides deliberately.

### Usage

```powershell
# After one-click + backend-up
.\scripts\r5-demo-v1-v2.ps1 -PrintOnly
.\scripts\r5-demo-v1-v2.ps1 -Login r5-demo-owner
```

### Expected outcomes (when verification is run)

| Step | Expected |
|---|---|
| Auth | `/api/v1/me` succeeds for `r5-demo-*` |
| Catalog | Group, campaign, style, V1+V2 revisions created |
| Surveys | V1 survey (then closed if needed); V1+V2 survey open |
| Messages | Card-attributed posts for V1 and V2 |
| Vision | Compare may succeed with live keys, or degrade with a clear warning |
| Approvals | None auto-posted |

**Status: PARTIAL**. The live catalog and feedback workflow and the separate authorized runtime gates are tracked independently below.

## Recommended full sequence

```powershell
Set-Location E:\shopper
.\scripts\r5-one-click.ps1

# Terminal A — backend
$env:CRAFTPILOT_BOOTSTRAP_LOGIN = 'r5-demo-owner'   # first run only
$secure = Read-Host 'Development password' -AsSecureString
$env:CRAFTPILOT_BOOTSTRAP_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
try { .\scripts\backend-up.ps1 } finally {
  Remove-Item Env:CRAFTPILOT_BOOTSTRAP_LOGIN,Env:CRAFTPILOT_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
}

# Terminal B — demo bootstrap
.\scripts\r5-demo-v1-v2.ps1 -Login r5-demo-owner

# Acceptance (Testcontainers; independent of local data/)
Set-Location backend
.\mvnw.cmd -B -ntp -Dtest=R5EvaluationHarnessTest,R5MessageReplayTest,R5FaultInjectionTest,R5SecurityAcceptanceTest test
```

Backend health:

- `http://127.0.0.1:18080/actuator/health/liveness`
- `http://127.0.0.1:18080/actuator/health/readiness`

Related setup: [docker-setup](docker-setup.md), [backend-setup](backend-setup.md).

## Recording a real verification

### Windows follow-up, 2026-11-17

- Windows 11, Java 21.0.11, Maven Wrapper 3.9.16 and Docker Desktop Linux engine 29.8.1. `r5-one-click.ps1` exited successfully. Compose reported PostgreSQL, Redis and MinIO healthy. The backend validated five Flyway migrations and upgraded the existing local schema from V1 to V5.
- `backend-up.ps1` launched in a separate hidden PowerShell process. `http://localhost:18080/actuator/health/liveness` and `/readiness` returned HTTP 200 with `UP`. Readiness included `db`, `redis`, `minio` and `readinessState`.
- A new `r5-demo-*` development principal and its own merchant were created using the existing bootstrap. The live script authenticated, created a new room and uploaded synthetic images. Initial upload failed with HTTP 400 because the request lacked multipart `kind`. The script now sends `kind=image`, uses distinct 64×64 PNGs, and keeps Basic authentication out of the native process command line.
- The real V1 survey returned one issue for the correctly attributed feedback. A read-only tenant-scoped query found the test evidence ID. The Evidence GET returned `ACTIVE` and `evidence/verify` returned it in `validIds`. The scoped Memory GET returned HTTP 200 with an empty list for this new merchant.
- `r5-demo-v1-v2.ps1` completed in live mode. After the configured closing deadline, the V1 survey reached `CLOSED` and the same room opened a survey covering V1 and V2. V2 feedback was attributed to that survey and revision with one issue. The V2 parent pointed to V1, whose previously recorded snapshot hash remained readable. Demo survey IDs were `3f49add9-08e1-4aad-8113-bcac56a76f5c` (V1) and `5a0e45ec-ddcb-4710-949f-3f973c1ab7f0` (V2).
- Interactive execution now fails on unavailable health or mismatched attribution. The script verifies both rounds of feedback and checks the V1 snapshot hash, survey history and V2 parent. Three PowerShell guard checks cover preview, remote-origin rejection and failure on an unavailable backend.
- Local Maven ran 54 tests with zero failures, errors or skips. This included the isolated R4 runtime gates and R5 evaluation, replay, fault and security tests. The final repackage step in the running backend's checkout failed because Windows held the JAR open. Run packaging in a separate checkout while keeping the original backend online. The PR workflow runs `clean verify` on its own checkout.
- Live Provider calls require authorization to spend quota. The local demo ran with Provider credentials omitted from the backend process. Chat, Embedding and Qwen-VL remain **BLOCKED** for this run. The existing isolated tests exercise Agent Run, Supervisor, Worker Handoff, Join, MCP, HITL, Merchant Decision and Memory. The Windows backend received no approval or decision command from this demo.
