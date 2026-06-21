#!/usr/bin/env python3
"""
Smoke test for ResumeScope.

Spins up the stack with docker compose (local mode), or runs against an
already-deployed instance (remote mode).

Usage (from project root):

  Local (default):
    python smoke_test/smoke.py

  Remote / AWS:
    BASE_URL=https://resume-scope.ecs.us-east-1.on.aws \\
    API_KEY=<your-api-key> \\
    SKIP_COMPOSE=1 \\
    python smoke_test/smoke.py

Optional env vars:
    BASE_URL       API base URL (default http://localhost:8086)
    API_KEY        X-API-Key header value; required when auth is enabled
    SKIP_COMPOSE   set to 1 to skip docker compose up/down (implied when BASE_URL is not localhost)
    KEEP_RUNNING   set to 1 to leave the local stack running after the test
"""

import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8086").rstrip("/")
API_KEY = os.environ.get("API_KEY", "")
# Remote mode: skip compose when BASE_URL points somewhere other than localhost
_is_remote = "localhost" not in BASE_URL and "127.0.0.1" not in BASE_URL
SKIP_COMPOSE = os.environ.get("SKIP_COMPOSE", "1" if _is_remote else "0") == "1"
KEEP_RUNNING = os.environ.get("KEEP_RUNNING", "0") == "1"

SMOKE_ENV_FILE = SCRIPT_DIR / ".env"
COMPOSE_CMD = [
    "docker", "compose",
    "--env-file", str(SMOKE_ENV_FILE),
    "-f", str(PROJECT_ROOT / "docker-compose.yml"),
    "-f", str(SCRIPT_DIR / "compose.smoke.yml"),
    "-p", "resume-scope-smoke",
]

CV_FILES = ["alice.pdf", "bob.pdf", "carol.pdf", "dave.pdf"]

# ---------------------------------------------------------------------------
# PDF generation (reportlab)
# ---------------------------------------------------------------------------

ALICE = [
    "Alice Johnson",
    "alice.johnson@example.com",
    "",
    "Experienced Hotel Housekeeper",
    "",
    "Experience:",
    "- 8 years as a senior housekeeper at the Grand Plaza Hotel (4-star).",
    "- Trained and led a team of 6 cleaning staff across 120 rooms per shift.",
    "- Certified in chemical safety, COSHH, and hospital-grade sanitation.",
    "- Specialised in deep-cleaning, linen handling, and turn-down service.",
    "- Consistent 98%+ guest cleanliness score over the last three years.",
    "",
    "Skills:",
    "- Bed making, bathroom sanitation, vacuuming, dusting, restocking amenities.",
    "- Eco-friendly cleaning products and waste segregation.",
    "- Reliable, punctual, and able to work weekends and night shifts.",
    "",
    "Languages: English (fluent), Spanish (fluent).",
]

BOB = [
    "Bob Smith",
    "bob.smith@example.com",
    "",
    "Cleaner",
    "",
    "Experience:",
    "- 1 year cleaning offices in the evenings (part-time).",
    "- 6 months as a kitchen porter, included some cleaning duties.",
    "- Comfortable using a vacuum cleaner and basic cleaning chemicals.",
    "",
    "Skills:",
    "- Punctual, hard-working.",
    "- Can work alone or in a small team.",
    "- No formal hotel housekeeping training, but willing to learn.",
    "",
    "Languages: English (native).",
]

CAROL = [
    "Carol Davis",
    "carol.davis@example.com",
    "",
    "Software Engineer",
    "",
    "Experience:",
    "- 3 years writing Python and JavaScript for a small SaaS startup.",
    "- Built REST APIs with FastAPI and Node.js; managed PostgreSQL databases.",
    "- Contributed to open source; comfortable with Git, Docker, CI/CD.",
    "- Once helped tidy up the office kitchen on Fridays.",
    "",
    "Skills:",
    "- Python, JavaScript, React, Node.js, PostgreSQL, Docker.",
    "- Problem-solving, code review, agile teamwork.",
    "- Looking for a tech role.",
    "",
    "Languages: English (native).",
]

DAVE = [
    "Dave Martinez",
    "dave.martinez@example.com",
    "",
    "Hospitality & IT Graduate",
    "",
    "Experience:",
    "- 2 years as a front-desk receptionist at a 3-star hotel.",
    "- Handled guest check-in/out, room assignments, and cleanliness complaints.",
    "- Part-time IT helpdesk during studies: basic Python scripting, SQL queries.",
    "- Built a small hotel booking web app as a final-year project (Flask, SQLite).",
    "",
    "Skills:",
    "- Customer service, hospitality standards, shift work.",
    "- Python (beginner), SQL, HTML/CSS.",
    "- Eager to move into tech; comfortable in hospitality environments.",
    "",
    "Languages: English (fluent), Portuguese (conversational).",
]


def generate_pdf(path: Path, lines: list) -> None:
    from reportlab.lib.pagesizes import LETTER
    from reportlab.pdfgen import canvas

    c = canvas.Canvas(str(path), pagesize=LETTER)
    c.setFont("Helvetica", 11)
    y = 750
    for line in lines:
        c.drawString(50, y, line)
        y -= 16
    c.showPage()
    c.save()


def ensure_pdfs() -> None:
    mapping = {
        "alice.pdf": ALICE,
        "bob.pdf": BOB,
        "carol.pdf": CAROL,
        "dave.pdf": DAVE,
    }
    missing = [name for name in mapping if not (SCRIPT_DIR / name).exists()]
    if not missing:
        return
    try:
        import reportlab  # noqa: F401
    except ImportError:
        print("ERROR: reportlab is not installed. Run: pip install reportlab")
        sys.exit(1)
    for name in missing:
        generate_pdf(SCRIPT_DIR / name, mapping[name])
        print(f"  generated {name}")


# ---------------------------------------------------------------------------
# HTTP helpers (no requests dependency — use urllib)
# ---------------------------------------------------------------------------

import urllib.error
import urllib.request


def _auth_headers(extra: dict | None = None) -> dict:
    h = dict(extra or {})
    if API_KEY:
        h["X-Api-Key"] = API_KEY
    return h


def _request(method: str, url: str, *, body=None, headers=None, timeout=30):
    req = urllib.request.Request(url, method=method, headers=_auth_headers(headers))
    if body is not None:
        if isinstance(body, dict):
            encoded = json.dumps(body).encode()
            req.add_header("Content-Type", "application/json")
            req.data = encoded
        else:
            req.data = body
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())


def get(path: str, **kw):
    return _request("GET", f"{BASE_URL}{path}", **kw)


def post_json(path: str, body: dict, headers: dict | None = None):
    return _request("POST", f"{BASE_URL}{path}", body=body, headers=headers or {})


def post_multipart(path: str, files: list[Path]) -> dict:
    boundary = uuid.uuid4().hex
    body_parts = []
    for fp in files:
        data = fp.read_bytes()
        body_parts.append(
            f'--{boundary}\r\n'
            f'Content-Disposition: form-data; name="files"; filename="{fp.name}"\r\n'
            f'Content-Type: application/pdf\r\n\r\n'.encode()
            + data
            + b'\r\n'
        )
    body = b''.join(body_parts) + f'--{boundary}--\r\n'.encode()
    req = urllib.request.Request(
        f"{BASE_URL}{path}",
        data=body,
        method="POST",
        headers=_auth_headers({"Content-Type": f"multipart/form-data; boundary={boundary}"}),
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


def wait_health(retries: int = 60, delay: float = 2.0) -> None:
    for i in range(retries):
        try:
            get("/health")
            print("  ok")
            return
        except Exception:
            time.sleep(delay)
    print("ERROR: app did not become healthy in time")
    sys.exit(1)


def poll_run(run_id: str, timeout_s: int = 360) -> str:
    deadline = time.time() + timeout_s
    last = ""
    while time.time() < deadline:
        data = get(f"/api/analysis-runs/{run_id}")
        status = data["status"]
        if status != last:
            print(f"  {status}")
            last = status
        if status == "COMPLETED":
            return status
        if status == "FAILED":
            print(f"ERROR: run {run_id} FAILED")
            sys.exit(1)
        time.sleep(2)
    print(f"ERROR: run {run_id} did not complete within {timeout_s}s")
    sys.exit(1)


def print_results(run_id: str, role_title: str) -> None:
    data = get(f"/api/analysis-runs/{run_id}/results")
    ranked = sorted(data["results"], key=lambda r: r["rank"])
    print(f"  --- {role_title} ---")
    for r in ranked:
        print(f"  rank {r['rank']}  score={r['overallScore']:>3}  {r['extractedName']}")


# ---------------------------------------------------------------------------
# SSE streaming (urllib + chunked read)
# ---------------------------------------------------------------------------

def sse_stream_until_terminal(run_id: str, timeout_s: int = 120) -> int:
    url = f"{BASE_URL}/api/analysis-runs/{run_id}/events"
    req = urllib.request.Request(url, headers=_auth_headers({"Accept": "text/event-stream"}))
    events_seen = 0
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        for raw_line in resp:
            line = raw_line.decode().rstrip("\r\n")
            if not line.startswith("data:"):
                continue
            payload = line[len("data:"):]
            try:
                obj = json.loads(payload)
            except json.JSONDecodeError:
                continue
            status = obj.get("status", "?")
            events_seen += 1
            print(f"  [event {events_seen}] {status}")
            if status in ("COMPLETED", "FAILED"):
                return events_seen
    print(f"ERROR: SSE stream ended without terminal status ({events_seen} events)")
    sys.exit(1)


# ---------------------------------------------------------------------------
# Job role definitions
# ---------------------------------------------------------------------------

ROLES = [
    {
        "title": "Hotel Housekeeper",
        "description": (
            "Full-time housekeeper at a 4-star hotel. Cleans guest rooms, replaces linen, "
            "restocks amenities, follows sanitation standards."
        ),
        "requirements": (
            "Prior hotel housekeeping experience preferred. Knowledge of cleaning chemicals "
            "and safety (COSHH). Able to work weekends and shifts. Physically fit, reliable, "
            "attention to detail. English required."
        ),
    },
    {
        "title": "Junior Software Developer",
        "description": (
            "Entry-level developer joining a small product team. Works on Python and JavaScript "
            "web services, contributes to SQL database design, and participates in code review."
        ),
        "requirements": (
            "CS degree, bootcamp certificate, or equivalent self-taught experience. "
            "Proficiency in Python or JavaScript. Familiarity with SQL and version control (Git). "
            "Strong problem-solving skills and eagerness to learn. Teamwork essential."
        ),
    },
]

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def preflight() -> None:
    if _is_remote:
        if not API_KEY:
            print("WARNING: API_KEY not set — requests may be rejected if auth is enabled on the server.")
        return

    if not SMOKE_ENV_FILE.exists():
        print(f"ERROR: missing {SMOKE_ENV_FILE}")
        print("  Create it and set OPENAI_API_KEY.")
        sys.exit(1)
    env_text = SMOKE_ENV_FILE.read_text()
    placeholders = ("sk-REPLACE_ME", "gsk_REPLACE_ME", "change-me")
    for line in env_text.splitlines():
        if line.startswith("OPENAI_API_KEY=") and any(p in line for p in placeholders):
            print("ERROR: OPENAI_API_KEY in .env is still a placeholder.")
            sys.exit(1)
    for name in CV_FILES:
        if not (SCRIPT_DIR / name).exists():
            return
    result = subprocess.run(["docker", "--version"], capture_output=True)
    if result.returncode != 0:
        print("ERROR: docker not found")
        sys.exit(1)


def main() -> None:
    preflight()

    n_roles = len(ROLES)
    total = 3 + n_roles * 3 + 1  # preflight + (create+upload+analyze) per role + SSE
    step = 1

    # 1. Generate PDFs
    print(f"[{step}/{total}] checking CV PDFs")
    step += 1
    ensure_pdfs()

    # 2. Start stack
    print(f"[{step}/{total}] starting stack")
    step += 1
    if not SKIP_COMPOSE:
        subprocess.run(COMPOSE_CMD + ["up", "-d", "--build"], check=True)
    elif _is_remote:
        print(f"  skipped (remote: {BASE_URL})")
    else:
        print("  skipped (SKIP_COMPOSE=1)")

    import atexit

    def teardown():
        if not KEEP_RUNNING and not SKIP_COMPOSE:
            print("\n[teardown] docker compose down -v")
            subprocess.run(COMPOSE_CMD + ["down", "-v"])

    atexit.register(teardown)

    # 3. Wait for health
    print(f"[{step}/{total}] waiting for health at {BASE_URL}/health ...")
    step += 1
    wait_health()

    cv_paths = [SCRIPT_DIR / name for name in CV_FILES]

    sse_role_id = None

    # One pass per role: create → upload → analyze
    for role_def in ROLES:
        label = role_def["title"]

        print(f"[{step}/{total}] {label} - creating role")
        step += 1
        role = post_json("/api/job-roles", role_def)
        role_id = role["id"]
        print(f"  role: {role_id}")

        print(f"[{step}/{total}] {label} - uploading {len(CV_FILES)} CVs")
        step += 1
        post_multipart(f"/api/job-roles/{role_id}/candidates", cv_paths)

        print(f"[{step}/{total}] {label} - triggering analysis")
        step += 1
        run = post_json(f"/api/job-roles/{role_id}/analyze", {})
        run_id = run["runId"]
        print(f"  run: {run_id}")
        poll_run(run_id)
        print_results(run_id, label)

        if sse_role_id is None:
            sse_role_id = role_id

    # SSE test
    print(f"[{step}/{total}] SSE - streaming run-status events")
    run = post_json(
        f"/api/job-roles/{sse_role_id}/analyze",
        {},
        headers={"Idempotency-Key": f"smoke-sse-{uuid.uuid4()}"},
    )
    sse_run_id = run["runId"]
    print(f"  run: {sse_run_id}")
    n = sse_stream_until_terminal(sse_run_id)
    print(f"  ok ({n} events, terminal reached)")

    print("\nDone.")


if __name__ == "__main__":
    main()
