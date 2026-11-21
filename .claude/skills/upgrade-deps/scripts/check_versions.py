#!/usr/bin/env python3
"""Report available upgrades and Spring Boot lockstep problems for ResumeScope.

Run from the repository root:  python .claude/skills/upgrade-deps/scripts/check_versions.py

Covers build.gradle (plugins, explicit coordinates, the jooq {} block, the Palantir formatter), the
Gradle wrapper, GitHub Actions in .github/workflows, and Docker base images. Exit code 1 means a
lockstep rule is broken in the *current* build (see LOCKSTEP RULES in SKILL.md); available upgrades
alone never fail the script.
"""

import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

MAVEN = "https://repo1.maven.org/maven2"
PLUGINS = "https://plugins.gradle.org/m2"
UNSTABLE = re.compile(r"(?i)(alpha|beta|snapshot|preview|[.-]m\d+$|[.-]rc\d*$|[.-]cr\d*$|-ea$)")

_cache = {}


def fetch(url):
    if url not in _cache:
        req = urllib.request.Request(url, headers={"User-Agent": "resume-scope-upgrade-deps"})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                _cache[url] = r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            _cache[url] = None if e.code == 404 else _raise(e, url)
    return _cache[url]


def _raise(e, url):
    raise RuntimeError(f"{url}: HTTP {e.code}") from e


def vkey(v):
    return [(0, int(p)) if p.isdigit() else (1, p) for p in re.split(r"[.\-]", v)]


def stable_versions(metadata_url):
    xml = fetch(metadata_url)
    if not xml:
        return []
    found = {m for m in re.findall(r"<version>([^<]+)</version>", xml) if not UNSTABLE.search(m)}
    return sorted(found, key=vkey)


def maven_versions(group, artifact):
    return stable_versions(f"{MAVEN}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml")


def plugin_versions(plugin_id):
    return stable_versions(f"{PLUGINS}/{plugin_id.replace('.', '/')}/{plugin_id}.gradle.plugin/maven-metadata.xml")


def prefix(v, n):
    return ".".join(re.split(r"[.\-]", v)[:n])


def summarize(current, versions):
    if not versions:
        return "  (no versions found)"
    latest = versions[-1]
    patch = [v for v in versions if prefix(v, 2) == prefix(current, 2)]
    minor = [v for v in versions if prefix(v, 1) == prefix(current, 1)]
    if vkey(latest) <= vkey(current):
        return "up to date"
    parts = []
    if patch and vkey(patch[-1]) > vkey(current):
        parts.append(f"patch {patch[-1]}")
    if minor and minor[-1] != (patch[-1] if patch else None) and vkey(minor[-1]) > vkey(current):
        parts.append(f"minor {minor[-1]}")
    if prefix(latest, 1) != prefix(current, 1):
        parts.append(f"MAJOR {latest}")
    return " | ".join(parts) or f"latest {latest}"


def boot_properties(boot_version):
    pom = fetch(f"{MAVEN}/org/springframework/boot/spring-boot-dependencies/{boot_version}/spring-boot-dependencies-{boot_version}.pom")
    return dict(re.findall(r"<([\w.-]+)\.version>([^<]+)<", pom or ""))


def spring_ai_target_boot(ai_version):
    """Boot version a Spring AI release was built against (its minor must match the project's Boot minor)."""
    pom = fetch(f"{MAVEN}/org/springframework/ai/spring-ai-autoconfigure-model-openai/{ai_version}/"
                f"spring-ai-autoconfigure-model-openai-{ai_version}.pom")
    for dep in re.findall(r"<dependency>(.*?)</dependency>", pom or "", re.S):
        if "<groupId>org.springframework.boot</groupId>" in dep:
            m = re.search(r"<version>([^<$]+)</version>", dep)
            if m:
                return m.group(1)
    return None


def spring_ai_for_boot_minor(boot_minor, limit=20):
    for v in reversed(maven_versions("org.springframework.ai", "spring-ai-bom")[-limit:]):
        target = spring_ai_target_boot(v)
        if target and prefix(target, 2) == boot_minor:
            return v
    return None


def github_latest_major(owner_repo):
    body = fetch(f"https://api.github.com/repos/{owner_repo}/releases/latest")
    if not body:
        return None
    tag = json.loads(body).get("tag_name", "")
    m = re.match(r"v?(\d+)", tag)
    return int(m.group(1)) if m else None


def docker_tag_exists(image, tag):
    repo = image if "/" in image else f"library/{image}"
    return fetch(f"https://hub.docker.com/v2/repositories/{repo}/tags/{tag}") is not None


def main():
    root = Path.cwd()
    gradle = (root / "build.gradle").read_text(encoding="utf-8")
    problems = []

    plugins = dict(re.findall(r"id\s+'([\w.]+)'\s+version\s+'([^']+)'", gradle))
    boot = plugins.get("org.springframework.boot")
    props = boot_properties(boot)
    jooq_managed, flyway_managed = props.get("jooq"), props.get("flyway")
    pinned = {"org.flywaydb.flyway": flyway_managed, "org.flywaydb:flyway-database-postgresql": flyway_managed,
              "org.jooq:jooq-meta-extensions": jooq_managed, "jooq": jooq_managed}

    def note(key):
        return f"   [pinned to Boot-managed {pinned[key]}]" if key in pinned else ""

    print("== build.gradle plugins")
    for pid, cur in plugins.items():
        versions = maven_versions("org.springframework.boot", "spring-boot") if pid == "org.springframework.boot" \
            else plugin_versions(pid)
        print(f"  {pid:40} {cur:12} {summarize(cur, versions)}{note(pid)}")

    print("== build.gradle explicit coordinates")
    coords = re.findall(r"['\"]([\w.-]+):([\w.-]+):([\w.-]+)['\"]", gradle)
    for g, a, cur in dict.fromkeys(coords):
        print(f"  {g + ':' + a:55} {cur:12} {summarize(cur, maven_versions(g, a))}{note(g + ':' + a)}")
    jooq_block = re.search(r"jooq\s*\{\s*version\s*=\s*'([^']+)'", gradle)
    palantir = re.search(r"palantirJavaFormat\('([^']+)'\)", gradle)
    if jooq_block:
        print(f"  {'jooq { version } (codegen)':55} {jooq_block.group(1):12} "
              f"{summarize(jooq_block.group(1), maven_versions('org.jooq', 'jooq'))}{note('jooq')}")
    if palantir:
        print(f"  {'palantirJavaFormat':55} {palantir.group(1):12} "
              f"{summarize(palantir.group(1), maven_versions('com.palantir.javaformat', 'palantir-java-format'))}")

    print("== Gradle wrapper")
    wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    cur_gradle = re.search(r"gradle-([\d.]+)-(?:bin|all)\.zip", wrapper).group(1)
    latest_gradle = json.loads(fetch("https://services.gradle.org/versions/current"))["version"]
    print(f"  {'gradle':40} {cur_gradle:12} "
          f"{'up to date' if vkey(latest_gradle) <= vkey(cur_gradle) else 'latest ' + latest_gradle}")

    coord_map = {f"{g}:{a}": v for g, a, v in coords}
    print(f"== Spring Boot lockstep (current Boot {boot})")
    checks = [
        ("jOOQ codegen (jooq { version })", jooq_block.group(1) if jooq_block else None, jooq_managed),
        ("jooqGenerator jooq-meta-extensions", coord_map.get("org.jooq:jooq-meta-extensions"), jooq_managed),
        ("Flyway Gradle plugin", plugins.get("org.flywaydb.flyway"), flyway_managed),
        ("buildscript flyway-database-postgresql", coord_map.get("org.flywaydb:flyway-database-postgresql"), flyway_managed),
    ]
    for label, have, want in checks:
        if have is None:
            continue
        ok = have == want
        print(f"  [{'OK' if ok else 'MISMATCH'}] {label} {have} {'==' if ok else '!='} Boot-managed {want}")
        if not ok:
            problems.append(f"{label} {have} != Boot-managed {want}")
    ai = re.search(r"spring-ai-bom:([\w.-]+)", gradle)
    if ai:
        target = spring_ai_target_boot(ai.group(1))
        ok = target is not None and prefix(target, 2) == prefix(boot, 2)
        print(f"  [{'OK' if ok else 'MISMATCH'}] Spring AI {ai.group(1)} targets Boot {target} (project Boot {boot})")
        if not ok:
            problems.append(f"Spring AI {ai.group(1)} targets Boot {target}, project is on {boot}")

    boot_versions = maven_versions("org.springframework.boot", "spring-boot")
    candidates = [v for v in boot_versions if prefix(v, 2) == prefix(boot, 2)][-1:] + \
                 [v for v in boot_versions if prefix(v, 1) == prefix(boot, 1)][-1:]
    print("== Boot upgrade candidates (set jOOQ / Flyway / Spring AI to these when moving Boot)")
    for cand in dict.fromkeys(candidates):
        p = boot_properties(cand)
        print(f"  Boot {cand}: jooq {p.get('jooq')}, flyway {p.get('flyway')}, "
              f"testcontainers {p.get('testcontainers')}, Spring AI {spring_ai_for_boot_minor(prefix(cand, 2)) or 'none yet'}")
    if prefix(boot_versions[-1], 1) != prefix(boot, 1):
        print(f"  Boot MAJOR {boot_versions[-1]} available: manual migration, never unattended")

    print("== GitHub Actions")
    uses = set()
    for wf in sorted((root / ".github/workflows").glob("*.y*ml")):
        uses |= set(re.findall(r"uses:\s*([\w.-]+/[\w.-]+)(?:/[\w./-]+)?@v(\d+)", wf.read_text(encoding="utf-8")))
    for repo, major in sorted(uses):
        latest = github_latest_major(repo)
        state = "up to date" if latest is None or latest <= int(major) else f"MAJOR v{latest}"
        print(f"  {repo:45} v{major:<11} {state}")

    print("== Docker images (floating tags get patches on rebuild; majors need a deliberate change)")
    files = [root / "Dockerfile", root / "docker-compose.yml", *root.glob("smoke_test/*.yml"), *root.rglob("src/test/**/*.java")]
    images = set()
    for f in files:
        if f.is_file():
            images |= set(re.findall(r"(?:FROM\s+|image:\s*|\")([a-z][a-z0-9./-]*):(\d+)(-[\w.-]+)?", f.read_text(encoding="utf-8")))
    for image, major, suffix in sorted(images):
        nxt = int(major) + 1
        newer = docker_tag_exists(image, f"{nxt}{suffix}")
        print(f"  {image}:{major}{suffix:<16} {'MAJOR ' + str(nxt) + suffix + ' available' if newer else 'no newer major'}")

    if problems:
        print("\nLOCKSTEP PROBLEMS:")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("\nLockstep OK.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
