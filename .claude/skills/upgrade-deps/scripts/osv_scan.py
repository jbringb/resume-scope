#!/usr/bin/env python3
"""Scan every *resolved* dependency of ResumeScope against the OSV vulnerability database.

Run from the repository root:  python .claude/skills/upgrade-deps/scripts/osv_scan.py

GitHub's Dependabot alerts only see the dependencies declared in build.gradle, not the transitive
ones Gradle resolves (Netty, Jackson, the Postgres drivers ...), so this resolves the real classpaths
and checks each artifact. It also flags artifact families resolved at more than one version across
classpaths (e.g. jOOQ codegen on 3.19 while runtime is on 3.21), which compiles fine but is wrong.

Exit code 1 when any vulnerability or mixed family is found.
"""

import json
import os
import re
import subprocess
import sys
import urllib.request
from collections import defaultdict

CONFIGURATIONS = ["runtimeClasspath", "testRuntimeClasspath", "jooqGenerator"]
# Families that must resolve to a single version everywhere (runtime, tests and codegen alike).
SINGLE_VERSION_GROUPS = ["org.jooq", "org.testcontainers", "org.flywaydb", "org.springframework.ai"]
GAV = re.compile(r"([\w.\-]+):([\w.\-]+)(?::([\w.\-]+))?(?: -> ([\w.\-]+))?")


def resolve(configuration):
    gradlew = os.path.join(os.getcwd(), "gradlew.bat" if os.name == "nt" else "gradlew")
    out = subprocess.run([gradlew, "-q", "dependencies", "--configuration", configuration],
                         capture_output=True, text=True, shell=os.name == "nt")
    if out.returncode != 0:
        sys.exit(f"gradle dependencies --configuration {configuration} failed:\n{out.stderr[-2000:]}")
    resolved = set()
    for line in out.stdout.splitlines():
        if "FAILED" in line:
            sys.exit(f"unresolved dependency in {configuration}: {line.strip()}")
        stripped = line.lstrip(" |+\\-")
        if stripped.endswith("(n)"):  # declared but not resolved
            continue
        m = GAV.match(stripped)
        if m and (m.group(3) or m.group(4)):
            resolved.add((m.group(1), m.group(2), m.group(4) or m.group(3)))
    return resolved


def fixed_versions(vuln, package):
    return sorted({e["fixed"] for a in vuln.get("affected", []) if a["package"]["name"] == package
                   for r in a.get("ranges", []) for e in r.get("events", []) if "fixed" in e})


def main():
    by_config = {c: resolve(c) for c in CONFIGURATIONS}
    gavs = sorted(set().union(*by_config.values()))
    print(f"resolved {len(gavs)} artifacts across {', '.join(CONFIGURATIONS)}")
    failed = False

    families = defaultdict(set)
    for g, a, v in gavs:
        if g in SINGLE_VERSION_GROUPS:
            families[g].add(v)
    for g, versions in sorted(families.items()):
        if len(versions) > 1:
            failed = True
            where = {c: sorted({f"{a}:{v}" for gg, a, v in s if gg == g}) for c, s in by_config.items()}
            print(f"MIXED VERSIONS {g}: {sorted(versions)}")
            for c, arts in where.items():
                if arts:
                    print(f"    {c}: {', '.join(arts)}")

    queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in gavs]
    req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries}).encode(),
                                 headers={"Content-Type": "application/json"})
    results = json.load(urllib.request.urlopen(req, timeout=60))["results"]
    hits = [(gavs[i], [v["id"] for v in r["vulns"]]) for i, r in enumerate(results) if r.get("vulns")]
    for (g, a, v), ids in hits:
        failed = True
        print(f"VULNERABLE {g}:{a}:{v}")
        for vid in ids:
            vuln = json.load(urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{vid}", timeout=30))
            severity = vuln.get("database_specific", {}).get("severity", "?")
            summary = vuln.get("summary", "").encode("ascii", "replace").decode()[:90]
            print(f"    {vid} {severity:8} fixed in {', '.join(fixed_versions(vuln, f'{g}:{a}')) or '?'} - {summary}")
    print(f"vulnerable artifacts: {len(hits)}, mixed families: {sum(len(v) > 1 for v in families.values())}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
