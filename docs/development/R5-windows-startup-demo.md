# R5 Windows one-click + V1→V2 demo

## Verification status

**PARTIALLY VERIFIED (local)** — `scripts/r5-one-click.ps1` executed successfully on this workstation (infra TCP healthy on 15432/17379/19000; jar packaged; Compose postgres/redis/minio healthy). `scripts/r5-demo-v1-v2.ps1 -PrintOnly` parses and prints steps. Full API demo against a live `backend-up.ps1` process and live vision still require an operator session (manual HITL/decision by design).

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

**Status: PENDING** (not executed here).

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
| Backend down | Warns and falls back to print-only |

### Identity isolation

- Bootstrap owner with `CRAFTPILOT_BOOTSTRAP_LOGIN=r5-demo-owner` (or another `r5-demo-*` login) on first `backend-up.ps1`.
- Script rejects logins that do not start with `r5-demo-`.

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

**Status: PENDING** (not executed here).

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

When you run the scripts locally, update this section with date, host notes, and PASS/FAIL per check. Until then keep **PENDING** — do not mark PASS from authorship alone.
