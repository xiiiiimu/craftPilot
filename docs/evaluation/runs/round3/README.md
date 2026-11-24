# Round 3 raw data (dev v3, gpt-4o-mini)

| Directory | What | Prompt | Consistency check |
|---|---|---|---|
| `s0-v2/` | step 0, full dev, repeats 3, C04/C32/C35 x5 | 2 | off |
| `s1-v4/` | step 1, same configuration | 4 (V10) | off |
| `diag-v4-parse/` | 5 cases that failed to parse in step 1, 1 run each | 4 | off |
| `diag-v41/` | the same 5 cases (`diag5/`), then all 28 cases that failed to parse in step 1 (`p28a/`, `p28b/`), 1 run each | 4.1 (V11) | off |
| `s1b/<batch>/` | step 1 rerun, full dev, same configuration as step 0 | 4.1 (V11) | off |
| `s1c/<batch>/` | step 1 rerun, full dev, same configuration as step 0 | 4.2 (V12) | off |

Files: `eval-runs.csv` (one row per run; since `f72a0df` the last column `raw_final_content` is the final turn's raw
model output, up to 4000 characters), `eval-turns.csv` (one row per turn), `eval-meta.json` (`s0-v2/` and `s1-v4/`
keep the per-batch metas as `eval-meta-parts.json`), `eval-contexts.jsonl.gz` (gzip; one JSON line per turn with the
exact messages sent to the model, its tool calls and its raw response).

Notes:

- `s0-v2/` and `s1-v4/` have no `eval-turns.csv`: it was deleted by a `clean verify` before it was copied out of
  `target/`. They also have no contexts, because the export (`f72a0df`) did not exist yet. Since `diag-v41/` every batch
  is copied here as soon as it finishes.
- `diag-v41/` and `s1b/` metas say `"git": {"commit": "f72a0df", "dirty": true}`: they ran with V11 (prompt v4.1) and
  its test changes in the working tree, before they were committed as `b90ffc4`. No other uncommitted change was present.
- `s1c/` metas say `"git": {"commit": "49d649a", "dirty": true}` for the same reason: V12 (prompt v4.2) and its test
  changes were in the working tree, not yet committed.
- `s1-v4/`: batch f2a was interrupted once before any case started (no model calls) and rerun; f1a / f1b are from the
  first attempt.
