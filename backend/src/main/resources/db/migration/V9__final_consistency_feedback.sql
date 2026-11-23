-- Round 3: final-answer consistency check (craftpilot.runtime.final-consistency-check, off by default).
--
-- When a research final answer's poll numbers or "mentioned by" counts disagree with what the tools returned, the
-- run gets one corrective turn. The correction is stored on the turn that produced the rejected answer so the next
-- context build can show the model its previous answer and what was wrong, and so a resumed run knows the one
-- allowed correction was already used:
--   {"message": "<text shown to the model>", "firstRecommendation": "...", "problems": ["...", ...]}
-- NULL for every other turn.
ALTER TABLE agent_turn ADD COLUMN consistency_feedback jsonb;
