"""Traceability: Requirements -> Epic -> User story -> Acceptance criterion -> Test -> Code (US-608).

Tests declare what they verify with JUnit tags, for example @Tag("US-101.1") or @Tag("A-50").
This script joins those tags with docs/01-clarifications.md, docs/02-backlog.md, ASSUMPTIONS.md and
(optionally) the Surefire/Failsafe XML reports, and renders docs/traceability.md.
"""
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

_BOLD = re.compile(r"\*\*(.+?)\*\*")


@dataclass
class AcceptanceCriterion:
    id: str
    story: str
    given: str
    when: str
    then: str


@dataclass
class Story:
    id: str
    title: str
    epic: str
    acs: list = field(default_factory=list)


@dataclass
class Epic:
    id: str
    name: str
    stories: list = field(default_factory=list)


@dataclass
class Backlog:
    epics: list
    stories: dict
    acs: dict
    requirement_stories: dict


@dataclass
class TestCase:
    cls: str
    method: str
    tags: list
    file: str
    code: list


# ---------------------------------------------------------------- requirements and assumptions

def parse_requirements(text):
    """Rows like '| R-01 | text |' from docs/01-clarifications.md."""
    reqs = {}
    for m in re.finditer(r"^\|\s*(R-\d+)\s*\|\s*(.+?)\s*\|\s*$", text, re.M):
        reqs[m.group(1)] = _BOLD.sub(r"\1", m.group(2))
    return reqs


def parse_assumptions(text):
    """Assumption IDs ('| A-13 | ...') from ASSUMPTIONS.md; tests may verify these directly."""
    return set(re.findall(r"^\|\s*(A-\d+)\s*\|", text, re.M))


# ---------------------------------------------------------------- backlog

def parse_backlog(text):
    epics, stories, acs, req_stories = [], {}, {}, {}
    epic = story = None
    in_traceability = False
    for line in text.splitlines():
        if m := re.match(r"^## (E\d+): (.+?)\s*$", line):
            epic = Epic(m.group(1), m.group(2))
            epics.append(epic)
            story, in_traceability = None, False
        elif line.startswith("## "):
            epic = story = None
            in_traceability = line.startswith("## Traceability")
        elif (m := re.match(r"^### (US-\d+): (.+?)(?: \([MSC]\))?\s*$", line)) and epic:
            story = Story(m.group(1), m.group(2), epic.id)
            epic.stories.append(story)
            stories[story.id] = story
        elif (m := re.match(r"^\|\s*(US-\d+\.\d+)\s*\|(.*?)\|(.*?)\|(.*?)\|\s*$", line)) and story:
            ac = AcceptanceCriterion(m.group(1), story.id, m.group(2).strip(), m.group(3).strip(), m.group(4).strip())
            story.acs.append(ac)
            acs[ac.id] = ac
        elif in_traceability and (m := re.match(r"^\|\s*(R-\d+)\b.*?\|(.*?)\|\s*$", line)):
            req_stories[m.group(1)] = re.findall(r"US-\d+", m.group(2))
    return Backlog(epics, stories, acs, req_stories)


# ---------------------------------------------------------------- tests

_TEST_ANNOTATIONS = ("@Test", "@ParameterizedTest", "@RepeatedTest")
_METHOD = re.compile(r"^\s*(?:public\s+|protected\s+|private\s+)?void\s+(\w+)\s*\(")
_TAG = re.compile(r'@Tag\("([^"]+)"\)')


def parse_java_tests(files, main_classes):
    """files: {path: source}. main_classes: {SimpleName: path} of production classes."""
    cases = []
    for path, source in sorted(files.items()):
        lines = source.splitlines()
        package = re.search(r"^package\s+([\w.]+);", source, re.M)
        class_idx = next((i for i, l in enumerate(lines) if re.search(r"\bclass\s+\w+", l)), None)
        if class_idx is None:
            continue
        cls_name = re.search(r"\bclass\s+(\w+)", lines[class_idx]).group(1)
        fqcn = f"{package.group(1)}.{cls_name}" if package else cls_name
        class_tags = _TAG.findall("\n".join(lines[:class_idx + 1]))

        starts, pending, pending_start = [], [], None
        for i in range(class_idx + 1, len(lines)):
            stripped = lines[i].strip()
            if stripped.startswith("@"):
                if not pending:
                    pending_start = i
                pending.append(stripped)
                continue
            m = _METHOD.match(lines[i])
            if m and any(a.startswith(_TEST_ANNOTATIONS) for a in pending):
                starts.append((pending_start, m.group(1), _TAG.findall(" ".join(pending))))
            pending = []

        class_level = "\n".join(lines[class_idx + 1:starts[0][0]]) if starts else ""
        for n, (start, method, tags) in enumerate(starts):
            end = starts[n + 1][0] if n + 1 < len(starts) else len(lines)
            body = "\n".join(lines[start:end])
            code = sorted(c for c in main_classes if re.search(rf"\b{c}\b", class_level + "\n" + body))
            cases.append(TestCase(fqcn, method, class_tags + tags, path, code))
    return cases


# ---------------------------------------------------------------- results

def parse_results(xml_texts):
    """Surefire/Failsafe XML -> {(class, method): passed|failed|skipped}; parameterized runs are merged."""
    rank = {"skipped": 0, "passed": 1, "failed": 2}
    results = {}
    for text in xml_texts:
        for tc in ET.fromstring(text).iter("testcase"):
            method = re.sub(r"[(\[].*$", "", tc.get("name"))
            key = (tc.get("classname"), method)
            if tc.find("failure") is not None or tc.find("error") is not None:
                status = "failed"
            elif tc.find("skipped") is not None:
                status = "skipped"
            else:
                status = "passed"
            if rank[status] >= rank[results.get(key, "skipped")]:
                results[key] = status
    return results


# ---------------------------------------------------------------- check

def check(backlog, assumptions, tests, results):
    """Problems that should fail the build: unknown or missing tags, and failing tagged tests."""
    valid = set(backlog.acs) | set(assumptions)
    errors = []
    for tc in tests:
        name = f"{tc.cls}.{tc.method}"
        errors += [f"{name}: unknown tag {tag}" for tag in tc.tags if tag not in valid]
        if not tc.tags:
            errors.append(f"{name}: no @Tag naming an acceptance criterion or assumption")
        if results.get((tc.cls, tc.method)) == "failed":
            errors.append(f"{name}: tagged test is failing")
    return errors


# ---------------------------------------------------------------- render

def _row(*cells):
    return "|" + "|".join(f" {c} " if c else " " for c in cells) + "|"


def _test_link(tc):
    return f"[{tc.cls.rsplit('.', 1)[-1]}.{tc.method}](../{tc.file})"


def _status(cases, results):
    if not cases:
        return "⚠️ no test"
    statuses = [results.get((tc.cls, tc.method)) for tc in cases]
    if all(st is None for st in statuses):
        return "▫ not run"
    if "failed" in statuses:
        return "❌ failed"
    if "passed" in statuses:
        return "✅ passed"
    return "⏭ skipped"


def render(requirements, backlog, tests, results, main_classes=None):
    by_tag = {}
    for tc in tests:
        for tag in tc.tags:
            by_tag.setdefault(tag, []).append(tc)
    code_paths = dict(main_classes or {})
    for tc in tests:  # fall back to the conventional path when no map is given
        for c in tc.code:
            code_paths.setdefault(c, None)

    def code_links(cases):
        names = sorted({c for tc in cases for c in tc.code})
        return ", ".join(f"[{n}](../{code_paths[n]})" if code_paths.get(n) else n for n in names)

    def tested(ac_ids):
        return sum(1 for a in ac_ids if by_tag.get(a))

    total = len(backlog.acs)
    covered = tested(backlog.acs)
    pct = round(100 * covered / total) if total else 0
    out = [
        "# Traceability matrix",
        "",
        "_Generated by `scripts/traceability.py`. Do not edit by hand: run "
        "`python3 scripts/traceability.py` to refresh it._",
        "",
        "Chain: [Requirements.md](../Requirements.md) → requirements R-xx ([01-clarifications.md](01-clarifications.md)) "
        "→ epics and stories ([02-backlog.md](02-backlog.md)) → acceptance criteria → tests (`@Tag(\"US-…\")`) → code.",
        "",
        f"- Acceptance criteria with at least one test: **{covered} of {total} ({pct}%)**",
        f"- Tests traced: {len(tests)}",
        "- A criterion marked tested has at least one test tagged with it. Whether that test fully proves the "
        "criterion is judged in the [test review](06-test-review.md).",
        "",
        "## Requirements → epics → stories",
        "",
        "| Requirement | Text | Epics | Stories | Criteria tested |",
        "|-------------|------|-------|---------|-----------------|",
    ]
    for rid, text in requirements.items():
        story_ids = backlog.requirement_stories.get(rid, [])
        stories = [backlog.stories[s] for s in story_ids if s in backlog.stories]
        epics = sorted({s.epic for s in stories}, key=lambda e: int(e[1:]))
        ac_ids = [ac.id for s in stories for ac in s.acs]
        out.append(_row(rid, text, ", ".join(epics), ", ".join(story_ids), f"{tested(ac_ids)} of {len(ac_ids)}"))

    for epic in backlog.epics:
        out += ["", f"## {epic.id}: {epic.name}"]
        for story in epic.stories:
            out += ["", f"### {story.id}: {story.title} ({epic.id}): {tested([a.id for a in story.acs])} of {len(story.acs)} criteria tested",
                    "", "| AC | Then | Tests | Code | Status |", "|----|------|-------|------|--------|"]
            for ac in story.acs:
                cases = by_tag.get(ac.id, [])
                tests_cell = ", ".join(_test_link(tc) for tc in cases)
                out.append(_row(ac.id, ac.then, tests_cell, code_links(cases), _status(cases, results)))

    assumption_tags = sorted({t for t in by_tag if t.startswith("A-")}, key=lambda a: int(a[2:]))
    if assumption_tags:
        out += ["", "## Assumptions verified directly by tests", "", "| Assumption | Tests | Code | Status |",
                "|------------|-------|------|--------|"]
        for a in assumption_tags:
            cases = by_tag[a]
            out.append(_row(a, ", ".join(_test_link(tc) for tc in cases), code_links(cases), _status(cases, results)))

    untagged = [tc for tc in tests if not tc.tags]
    if untagged:
        out += ["", "## Untagged tests (fix these)", ""] + [f"- {_test_link(tc)}" for tc in untagged]
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- command line

def main(argv=None):
    import argparse
    import glob
    import os
    import sys

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    parser.add_argument("--results", nargs="*", default=[], help="directories with TEST-*.xml reports")
    parser.add_argument("--out", default="docs/traceability.md")
    parser.add_argument("--check", action="store_true", help="exit 1 on unknown/missing tags or failing tests")
    args = parser.parse_args(argv)
    root = os.path.abspath(args.root)

    def read(rel):
        with open(os.path.join(root, rel), encoding="utf-8") as f:
            return f.read()

    def rel(path):
        return os.path.relpath(path, root).replace(os.sep, "/")

    main_classes = {os.path.splitext(os.path.basename(p))[0]: rel(p)
                    for p in glob.glob(os.path.join(root, "src/main/java/**/*.java"), recursive=True)}
    main_classes.pop("TradeStoreApplication", None)
    test_files = {rel(p): open(p, encoding="utf-8").read()
                  for p in glob.glob(os.path.join(root, "src/test/java/**/*.java"), recursive=True)}
    xml = []
    for d in args.results:
        for p in sorted(glob.glob(os.path.join(root, d, "TEST-*.xml"))):
            xml.append(open(p, encoding="utf-8").read())

    requirements = parse_requirements(read("docs/01-clarifications.md"))
    backlog = parse_backlog(read("docs/02-backlog.md"))
    assumptions = parse_assumptions(read("ASSUMPTIONS.md"))
    tests = parse_java_tests(test_files, main_classes)
    results = parse_results(xml)

    out_path = os.path.join(root, args.out)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(render(requirements, backlog, tests, results, main_classes))
    print(f"Wrote {args.out}: {len(tests)} tests, {len(backlog.acs)} acceptance criteria")

    if args.check:
        errors = check(backlog, assumptions, tests, results)
        for e in errors:
            print(f"ERROR {e}", file=sys.stderr)
        return 1 if errors else 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
