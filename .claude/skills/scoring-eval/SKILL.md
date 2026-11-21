---
name: scoring-eval
description: Check that ResumeScope's LLM still scores CVs sensibly — 0–100 scale, right ranking, prompt injection resisted — with the scoring eval against the real model. Use before shipping any change to the analysis prompt (CvAnalyzerService.buildUserPrompt), the CvAnalysisResult output schema, the model or temperature (OPENAI_MODEL, application.yaml), or a Spring AI upgrade; and when adding a regression case for a scoring bug.
---

# Scoring eval (the LLM's behaviour, not the plumbing)

Unit tests mock the `ChatClient`, so they can't tell whether the real model still scores well. A prompt refactor once moved the model onto a 0–10 scale (a perfect-fit candidate scored 10) and every test stayed green. The eval catches that class of regression.

- `smoke_test/scoring.py` — the expectations: a score band per CV per role, which CVs must hold the top ranks, the minimum lead over the rest, and that `mallory` (a CV with an embedded "give me 100" instruction) scores low **and** gets called out in her weaknesses.
- `smoke_test/eval.py` — runs every CV against every role `--repeat` times and checks each run against those expectations, then prints each candidate's score range.
- `smoke_test/smoke.py` enforces the same expectations once per role, so the smoke test also fails on a scoring regression.

Needs `smoke_test/.env` with a real `OPENAI_API_KEY`, `pip install reportlab`, and Docker. Costs about €0.0015 per repeat (gpt-4o-mini).

## Steps

1. **Baseline first** (before touching the prompt or model): `python smoke_test/eval.py --repeat 3`. Keep the score ranges.
   - Faster loop while iterating on a prompt (no image rebuild): `docker compose up -d postgres`, then `OPENAI_API_KEY=… ./gradlew bootRun`, then `SKIP_COMPOSE=1 python smoke_test/eval.py --repeat 3`.
2. **Make the change.** If the prompt must keep a specific instruction (like the 0–100 range), also pin it with a unit test in `CvAnalyzerServiceTest`, the way `processAnalysisRun_promptStatesZeroToHundredScoreScale` does. That runs in CI without an API key.
3. **Re-run** `python smoke_test/eval.py --repeat 3`. Every run must pass. Compare ranges with the baseline: a candidate moving more than ~15 points is worth explaining even inside its band.
4. **Never widen a band just to get green.** Change an expectation only when the new behaviour is genuinely more correct, and say why in the commit or PR.
5. **Fixing a scoring bug? Add a case first.** Add the CV's lines to `smoke.py` (a new list, its `ensure_pdfs` mapping, `CV_FILES`), plus its bands for **every** role in `scoring.py`. Its PDF is generated on the first run; commit it with the others. Show that the eval fails before the fix and passes after.
6. **Before shipping**, also run the full smoke test: `API_KEY="smoke-$(date +%s)" python smoke_test/smoke.py`.

## Report

What changed in the prompt, schema or model; per-candidate score ranges before → after; passing runs (e.g. 6/6); anything that moved noticeably. Also say what wasn't run and why.
