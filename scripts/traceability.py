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
