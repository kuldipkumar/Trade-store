"""Builds portal/data/portal.json: the single-pane view of requirements, tests, design, pipeline, code and security.

Reads the repository's own documents and, when given, the reports of a CI run (surefire/failsafe XML, JaCoCo CSV,
Trivy tables, the ZAP JSON). Usage:

    python3 scripts/portal_data.py --reports <dir with CI artifacts> --out portal/data/portal.json
"""
import argparse
import csv
import datetime
import glob
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import zap_gate  # noqa: E402

REPO = "kuldipkumar/Trade-store"
PRIORITY = {"M": "Must", "S": "Should", "C": "Could"}
SEVERITIES = ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"]

# Responsibilities from docs/05-lld.md section 1, one line each, for the code-modules view.
MODULES = {
    "api": "REST endpoints, request size limit, error mapping to the standard ApiErrorResponse, CSV export",
    "messaging": "Kafka listener, topics, retry and dead-letter handling, broker health check",
    "service": "Use cases: ingestion, queries, audit report, expiry, outbox relay, metrics",
    "service.validation": "One class per business rule: fields, version, maturity date",
    "service.parsing": "Strict JSON parsing shared by both channels",
    "domain": "Trades, outcomes, violations and domain exceptions (immutable records)",
    "persistence": "Ports for trades, outbox, audit and history",
    "persistence.sql": "PostgreSQL adapters: version-guarded upsert, outbox, batched expiry",
    "persistence.nosql": "MongoDB adapters: append-only audit trail and trade history, indexes",
    "scheduler": "Daily expiry (with startup catch-up) and the outbox relay",
    "config": "Validated trade-store.* settings, clock and scheduling",
}


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def anchor(heading):
    """The anchor GitHub generates for a Markdown heading."""
    text = heading.strip().lower().replace("`", "")
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def parse_backlog(text):
    """Epics with their stories (title, priority, narrative, number of acceptance criteria, heading anchor)."""
    epics = []
    for m in re.finditer(r"^\| (E\d) \| ([^|]+?) \| (US-\d+) to (US-\d+) \|", text, re.M):
        epics.append({"id": m.group(1), "name": m.group(2).strip(), "stories": []})
    by_prefix = {e["id"][1]: e for e in epics}
    blocks = re.split(r"^### ", text, flags=re.M)[1:]
    for block in blocks:
        heading = block.splitlines()[0]
        m = re.match(r"(US-(\d)\d\d): (.+?) \(([MSC])\)", heading)
        if not m:
            continue
        narrative = re.search(r"^\*(As .+?)\*$", block, re.M)
        story = {
            "id": m.group(1), "title": m.group(3), "priority": PRIORITY[m.group(4)],
            "narrative": narrative.group(1) if narrative else "",
            "criteria": len(re.findall(r"^\| " + re.escape(m.group(1)) + r"\.\d+ \|", block, re.M)),
            "anchor": anchor(heading),
        }
        epic = by_prefix.get(m.group(2))
        if epic:
            epic["stories"].append(story)
    return epics


def parse_requirements(traceability):
    rows = []
    for m in re.finditer(r"^\| (R-\d+) \| (.+?) \| ([^|]*) \| ([^|]*) \| ([^|]*) \|", traceability, re.M):
        rows.append({"id": m.group(1), "text": m.group(2).strip(), "epics": m.group(3).strip(),
                     "stories": re.findall(r"US-\d+", m.group(4)), "tested": m.group(5).strip()})
    return rows


def trivy_totals(text):
    totals = {s: 0 for s in SEVERITIES}
    for m in re.finditer(r"Total: \d+ \(([^)]*)\)", text):
        for part in m.group(1).split(","):
            name, value = part.strip().split(": ")
            totals[name] += int(value)
    totals["total"] = sum(totals[s] for s in SEVERITIES)
    return totals


def test_kind(path):
    name = os.path.basename(path)
    if name.endswith("PT.java"):
        return "performance"
    if name.endswith("IT.java"):
        return "integration"
    if "/repo/" in path.replace(os.sep, "/"):
        return "conformance"
    return "unit"


def count_tests(source):
    count = len(re.findall(r"^\s*@(?:Test|ParameterizedTest)\b", source, re.M))
    tags = sorted(set(re.findall(r'@Tag\("([^"]+)"\)', source)))
    return count, tags


def test_results(reports):
    """Per test class: run, failed, skipped and seconds, from the JUnit XML reports of the CI run."""
    results = {}
    for path in glob.glob(os.path.join(reports, "**", "TEST-*.xml"), recursive=True):
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        name = suite.get("name", "").rsplit(".", 1)[-1]
        results[name] = {
            "run": int(suite.get("tests", 0)),
            "failed": int(suite.get("failures", 0)) + int(suite.get("errors", 0)),
            "skipped": int(suite.get("skipped", 0)),
            "seconds": round(float(suite.get("time", 0) or 0), 1),
        }
    return results


def collect_tests(reports):
    results = test_results(reports) if reports else {}
    classes = []
    for path in sorted(glob.glob("src/test/java/**/*.java", recursive=True)):
        count, tags = count_tests(read(path))
        if count == 0:
            continue
        name = os.path.basename(path)[:-5]
        rel = path.replace(os.sep, "/")
        classes.append({"name": name, "kind": test_kind(rel), "tests": count, "tags": tags,
                        "path": rel, "result": results.get(name)})
    return classes


def coverage(reports):
    files = glob.glob(os.path.join(reports, "**", "jacoco.csv"), recursive=True) if reports else []
    if not files:
        return None
    packages = {}
    with open(files[0], encoding="utf-8") as f:
        for row in csv.DictReader(f):
            pkg = row["PACKAGE"].replace("com.tradestore.", "").replace("com.tradestore", "(root)")
            p = packages.setdefault(pkg, [0, 0])
            p[0] += int(row["LINE_COVERED"])
            p[1] += int(row["LINE_MISSED"]) + int(row["LINE_COVERED"])
    covered = sum(p[0] for p in packages.values())
    total = sum(p[1] for p in packages.values())
    return {"line": round(100 * covered / total, 1) if total else None,
            "packages": {k: round(100 * v[0] / v[1], 1) for k, v in sorted(packages.items()) if v[1]}}


def modules(cov):
    rows = []
    base = "src/main/java/com/tradestore"
    for key, responsibility in MODULES.items():
        folder = os.path.join(base, *key.split("."))
        files = sorted(glob.glob(os.path.join(folder, "*.java")))
        loc = sum(len([line for line in read(f).splitlines() if line.strip()]) for f in files)
        rows.append({"package": key, "responsibility": responsibility,
                     "classes": [os.path.basename(f)[:-5] for f in files], "loc": loc,
                     "path": folder.replace(os.sep, "/"),
                     "coverage": (cov or {}).get("packages", {}).get(key)})
    return rows


def design():
    adrs = []
    for path in sorted(glob.glob("docs/adr/*.md")):
        first = read(path).splitlines()[0].lstrip("# ").strip()
        adrs.append({"title": first, "path": path})
    diagrams = []
    for path in sorted(glob.glob("docs/diagrams/*.puml")):
        title = re.search(r"^title (.+)$", read(path), re.M)
        name = os.path.basename(path)[:-5]
        diagrams.append({"name": name, "title": title.group(1).split("\\n")[0] if title else name,
                         "phase": name.split("-")[0], "kind": name.split("-")[1], "source": path,
                         "svg": f"diagrams/{name}.svg"})
    return {"adrs": adrs, "diagrams": diagrams}


def decisions():
    lld = read("docs/05-lld.md")
    found = [{"id": m.group(1), "text": re.sub(r"[*`\[\]]|\(adr/[^)]*\)", "", m.group(2)).strip()}
             for m in re.finditer(r"^\| \*\*(D\d)\*\* \| (.+?) \|", lld, re.M)]
    review = read("docs/06-test-review.md")
    for d in ("D8", "D9"):
        m = re.search(r"## Decision " + d + r" \(([^)]*)\)", review)
        if m and d not in [x["id"] for x in found]:
            label = {"D8": "US-501.3 reworded for the transactional outbox",
                     "D9": "A database outage never dead-letters a trade (option C)"}[d]
            found.append({"id": d, "text": f"{label} ({m.group(1)})"})
    return found


def metrics():
    rows = []
    for m in re.finditer(r"^\| (Counter|Timer|Gauges|Timer \(read side\)) \| (.+) \|$", read("docs/05-lld.md"), re.M):
        names = [n for n in re.findall(r"`([a-z_]+)(?:\{[^`]*\})?`", m.group(2)) if n.startswith(("trade_", "http_"))]
        description = re.sub(r"`[^`]*`:?", "", m.group(2)).strip(" :;,")
        rows.append({"type": m.group(1), "names": names, "description": description})
    return rows


def performance(reports):
    text = ""
    for candidate in ([os.path.join(reports, "performance", "summary.md")] if reports else []) + ["README.md"]:
        if os.path.exists(candidate):
            text = read(candidate)
            break
    burst = re.search(r"Burst of 10,000 stream trades \| All stored in ([\d.]+) s", text)
    rest = re.search(r"REST at 50 requests/s for 60 s \| p50 ([\d.]+) ms, p95 ([\d.]+) ms, p99 ([\d.]+) ms", text)
    return {"burstSeconds": float(burst.group(1)) if burst else None,
            "restP50": float(rest.group(1)) if rest else None,
            "restP95": float(rest.group(2)) if rest else None,
            "restP99": float(rest.group(3)) if rest else None,
            "measured": "2026-11-22, single instance on Testcontainers"}


def security(reports):
    def text(pattern):
        files = glob.glob(os.path.join(reports, "**", pattern), recursive=True) if reports else []
        return read(files[0]) if files else None

    result = {}
    for key, pattern in (("dependencies", "trivy-dependencies.txt"), ("image", "trivy-image.txt"),
                         ("full", "trivy-full-report.txt")):
        content = text(pattern)
        result[key] = trivy_totals(content) if content is not None else None
    zap = glob.glob(os.path.join(reports, "**", "zap-report.json"), recursive=True) if reports else []
    if zap:
        counts, titles = zap_gate.summarise(json.loads(read(zap[0])))
        result["dast"] = {"counts": counts, "medium": titles["Medium"], "low": titles["Low"]}
    else:
        result["dast"] = None
    return result


def git_sha():
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def build(reports):
    traceability = read("docs/traceability.md")
    tested = re.search(r"Acceptance criteria with at least one test: \*\*(\d+) of (\d+)", traceability)
    epics = parse_backlog(read("docs/02-backlog.md"))
    cov = coverage(reports)
    tests = collect_tests(reports)
    return {
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "repo": REPO,
        "commit": os.environ.get("PORTAL_SHA") or git_sha(),
        "ciRun": os.environ.get("PORTAL_RUN_ID"),
        "requirements": parse_requirements(traceability),
        "epics": epics,
        "counts": {
            "stories": sum(len(e["stories"]) for e in epics),
            "criteria": int(tested.group(2)) if tested else None,
            "criteriaTested": int(tested.group(1)) if tested else None,
            "clarifications": len(re.findall(r"^\| C-\d+ \|", read("docs/01-clarifications.md"), re.M)),
            "assumptions": len(re.findall(r"^\| A-\d+ \|", read("ASSUMPTIONS.md"), re.M)),
            "prompts": len(re.findall(r"^\| \d+ \|", read("PROMPTS.md"), re.M)),
        },
        "decisions": decisions(),
        "tests": tests,
        "coverage": cov,
        "modules": modules(cov),
        "design": design(),
        "security": security(reports),
        "metrics": metrics(),
        "performance": performance(reports),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", help="folder holding the downloaded CI artifacts")
    parser.add_argument("--out", default="portal/data/portal.json")
    args = parser.parse_args()
    data = build(args.reports)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
    print(f"Wrote {args.out}: {data['counts']['stories']} stories, {len(data['tests'])} test classes, "
          f"coverage {(data['coverage'] or {}).get('line')}")


if __name__ == "__main__":
    main()
