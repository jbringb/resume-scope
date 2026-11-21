#!/usr/bin/env python3
"""
Scoring eval for ResumeScope's LLM analysis.

Runs every smoke-test CV against every smoke-test role, --repeat times, and checks each run against
the expectations in scoring.py (score bands, ranking, margin, prompt-injection handling). Unlike
smoke.py it checks nothing else, and repeating shows how stable the scores are, so use it when
changing the prompt, output schema, model or Spring AI version.

Usage (from project root):

  Against the smoke Docker stack (builds the image, needs smoke_test/.env):
    python smoke_test/eval.py --repeat 3

  Against an app you already run (e.g. ./gradlew bootRun -- fastest loop when editing the prompt):
    SKIP_COMPOSE=1 python smoke_test/eval.py --repeat 3

Env vars are the same as smoke.py (BASE_URL, API_KEY, SKIP_COMPOSE, KEEP_RUNNING).
Costs about EUR 0.0015 per repeat with gpt-4o-mini. Exit code 1 when any run misses an expectation.
"""

import argparse
import atexit
import subprocess
import uuid
from pathlib import Path

import smoke
from scoring import check_scores


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--repeat", type=int, default=3, help="analysis runs per role (default 3)")
    args = parser.parse_args()

    smoke.preflight()
    smoke.ensure_pdfs()
    if not smoke.SKIP_COMPOSE:
        subprocess.run(smoke.COMPOSE_CMD + ["up", "-d", "--build"], check=True)
        if not smoke.KEEP_RUNNING:
            atexit.register(lambda: subprocess.run(smoke.COMPOSE_CMD + ["down", "-v"]))
    print(f"waiting for health at {smoke.BASE_URL}/health ...")
    smoke.wait_health()

    cv_paths = [smoke.SCRIPT_DIR / name for name in smoke.CV_FILES]
    failed_runs = 0
    for role_def in smoke.ROLES:
        title = role_def["title"]
        role_id = smoke.post_json("/api/job-roles", role_def)["id"]
        uploaded = smoke.post_multipart(f"/api/job-roles/{role_id}/candidates", cv_paths)
        stems = {c["id"]: Path(c["originalFilename"]).stem for c in uploaded}
        scores = {stem: [] for stem in stems.values()}

        print(f"\n=== {title} ({args.repeat} runs)")
        for i in range(1, args.repeat + 1):
            # A fresh Idempotency-Key per run, so each trigger really starts a new analysis.
            run = smoke.post_json(f"/api/job-roles/{role_id}/analyze", {},
                                  headers={"Idempotency-Key": f"eval-{uuid.uuid4()}"})
            smoke.poll_run(run["runId"])
            results = smoke.get(f"/api/analysis-runs/{run['runId']}/results")["results"]
            for r in results:
                scores[stems[r["candidateId"]]].append(r["overallScore"])
            failures = check_scores(title, results, stems)
            failed_runs += bool(failures)
            print(f"  run {i}: {'ok' if not failures else 'FAILED'}")
            for f in failures:
                print(f"    - {f}")

        for stem, values in sorted(scores.items(), key=lambda kv: -max(kv[1], default=0)):
            spread = f"{min(values)}-{max(values)}" if values else "-"
            print(f"  {stem:10} scores {values}  range {spread}")

    total = args.repeat * len(smoke.ROLES)
    print(f"\n{total - failed_runs}/{total} runs met every expectation")
    return 1 if failed_runs else 0


if __name__ == "__main__":
    raise SystemExit(main())
