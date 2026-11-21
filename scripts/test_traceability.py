"""Unit tests for traceability.py. Run: python3 -m unittest discover -s scripts -p 'test_*.py'"""
import unittest

import traceability as t

CLARIFICATIONS = """
| ID | Requirement |
|----|-------------|
| R-01 | Build a service that receives trades through a stream. |
| R-03 | **Version rule:** reject a lower version. |
"""

ASSUMPTIONS = """
| A-13 | A maturity date equal to today is valid. | Agreed |
| A-50 | **Configurability:** everything is externalised. | Agreed |
"""

BACKLOG = """
## E1: Trade versioning and storage

### US-101: Store a new trade (M)
*As a user ...*

| AC | Given | When | Then |
|----|-------|------|------|
| US-101.1 | No trade `T1` is stored | `T1` v1 is submitted | `T1` v1 is stored |
| US-101.2 | No trade | sender sets createdDate | values are ignored |

### US-104: Reject a lower version (M)
| AC | Given | When | Then |
|----|-------|------|------|
| US-104.1 | `T2` v2 is stored | `T2` v1 is submitted | A `LowerVersionException` is raised |

## E3: Stream ingestion

### US-301: Consume trades from the stream (M)
| AC | Given | When | Then |
|----|-------|------|------|
| US-301.1 | running | message published | stored |

## Traceability: requirements to stories

| Requirement | Stories |
|-------------|---------|
| R-01 Stream ingestion, high volume | US-301 |
| R-03 Version rule + exception | US-101, US-104 |
"""

JAVA_TEST = """
package com.tradestore.service;

import com.tradestore.domain.Trade;
import org.junit.jupiter.api.Tag;

class TradeIngestionServiceTest {

    private final TradeIngestionService service = null;

    @Test
    @Tag("US-101.1")
    void should_storeTrade_when_new() {
        Trade t = null;
        service.ingest(null);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @Tag("US-104.1")
    @Tag("A-13")
    void should_reject_when_lower(int v) {
        LowerVersionValidator x = null;
    }

    @Test
    void should_doSomething_when_untagged() {
    }
}
"""

SUREFIRE_XML = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.tradestore.service.TradeIngestionServiceTest">
  <testcase name="should_storeTrade_when_new" classname="com.tradestore.service.TradeIngestionServiceTest" time="0.01"/>
  <testcase name="should_reject_when_lower(int)[1]" classname="com.tradestore.service.TradeIngestionServiceTest" time="0.01"/>
  <testcase name="should_reject_when_lower(int)[2]" classname="com.tradestore.service.TradeIngestionServiceTest" time="0.01">
    <failure message="boom" type="java.lang.AssertionError">boom</failure>
  </testcase>
  <testcase name="should_doSomething_when_untagged" classname="com.tradestore.service.TradeIngestionServiceTest" time="0.01">
    <skipped/>
  </testcase>
</testsuite>
"""


class ParseRequirementsTest(unittest.TestCase):

    def test_should_readRequirementIdsAndText_when_tableRowsArePresent(self):
        reqs = t.parse_requirements(CLARIFICATIONS)
        self.assertEqual(reqs, {"R-01": "Build a service that receives trades through a stream.",
                                "R-03": "Version rule: reject a lower version."})

    def test_should_readAssumptionIds_when_tableRowsArePresent(self):
        self.assertEqual(t.parse_assumptions(ASSUMPTIONS), {"A-13", "A-50"})


class ParseBacklogTest(unittest.TestCase):

    def setUp(self):
        self.backlog = t.parse_backlog(BACKLOG)

    def test_should_groupStoriesUnderTheirEpic_when_backlogIsParsed(self):
        self.assertEqual([(e.id, e.name, [s.id for s in e.stories]) for e in self.backlog.epics],
                         [("E1", "Trade versioning and storage", ["US-101", "US-104"]),
                          ("E3", "Stream ingestion", ["US-301"])])

    def test_should_readAcceptanceCriteriaWithTheirThenClause_when_storyHasATable(self):
        story = self.backlog.stories["US-101"]
        self.assertEqual(story.title, "Store a new trade")
        self.assertEqual([(ac.id, ac.then) for ac in story.acs],
                         [("US-101.1", "`T1` v1 is stored"), ("US-101.2", "values are ignored")])

    def test_should_mapRequirementsToStories_when_traceabilityTableIsPresent(self):
        self.assertEqual(self.backlog.requirement_stories, {"R-01": ["US-301"], "R-03": ["US-101", "US-104"]})


class ParseJavaTestsTest(unittest.TestCase):

    def setUp(self):
        files = {"src/test/java/com/tradestore/service/TradeIngestionServiceTest.java": JAVA_TEST}
        main = {"TradeIngestionService": "src/main/java/com/tradestore/service/TradeIngestionService.java",
                "Trade": "src/main/java/com/tradestore/domain/Trade.java",
                "LowerVersionValidator": "src/main/java/com/tradestore/service/validation/LowerVersionValidator.java"}
        self.tests = t.parse_java_tests(files, main)

    def test_should_findEveryTestMethodWithItsTags_when_annotationsPrecedeTheMethod(self):
        self.assertEqual([(c.cls, c.method, c.tags) for c in self.tests], [
            ("com.tradestore.service.TradeIngestionServiceTest", "should_storeTrade_when_new", ["US-101.1"]),
            ("com.tradestore.service.TradeIngestionServiceTest", "should_reject_when_lower", ["US-104.1", "A-13"]),
            ("com.tradestore.service.TradeIngestionServiceTest", "should_doSomething_when_untagged", []),
        ])

    def test_should_listProductionClassesUsedByEachTest_when_bodyOrUsedFieldsReferenceThem(self):
        # A field's type counts only for the methods whose body uses that field (review of PR #53).
        self.assertEqual(self.tests[0].code, ["Trade", "TradeIngestionService"])  # uses field `service`
        self.assertEqual(self.tests[1].code, ["LowerVersionValidator"])  # does not use `service`


class MultiLineAnnotationTest(unittest.TestCase):

    def test_should_findTheTest_when_anAnnotationSpansSeveralLines(self):
        java = """package p;
class ATest {
    @ParameterizedTest
    @ValueSource(strings = {"a",
            "b", "c"})
    @Tag("US-105.3")
    void should_x_when_y(String v) {
    }
}
"""
        tests = t.parse_java_tests({"ATest.java": java}, {})
        self.assertEqual([(c.method, c.tags) for c in tests], [("should_x_when_y", ["US-105.3"])])


class ParseResultsTest(unittest.TestCase):

    def test_should_aggregateParameterizedRunsAndStatuses_when_reportsAreRead(self):
        results = t.parse_results([SUREFIRE_XML])
        cls = "com.tradestore.service.TradeIngestionServiceTest"
        self.assertEqual(results, {(cls, "should_storeTrade_when_new"): "passed",
                                   (cls, "should_reject_when_lower"): "failed",
                                   (cls, "should_doSomething_when_untagged"): "skipped"})


def _fixture(with_results):
    reqs = t.parse_requirements(CLARIFICATIONS)
    backlog = t.parse_backlog(BACKLOG)
    main = {"TradeIngestionService": "src/main/java/com/tradestore/service/TradeIngestionService.java",
            "Trade": "src/main/java/com/tradestore/domain/Trade.java"}
    tests = t.parse_java_tests({"src/test/java/com/tradestore/service/TradeIngestionServiceTest.java": JAVA_TEST}, main)
    results = t.parse_results([SUREFIRE_XML]) if with_results else {}
    return reqs, backlog, tests, results, main


class CheckTest(unittest.TestCase):

    def test_should_reportUnknownUntaggedAndFailingTests_when_checked(self):
        reqs, backlog, tests, results, _ = _fixture(with_results=True)
        tests.append(t.TestCase("x.TypoTest", "should_x_when_y", ["US-101.9"], "TypoTest.java", []))

        errors = t.check(backlog, {"A-13"}, tests, results)

        self.assertEqual(errors, [
            "com.tradestore.service.TradeIngestionServiceTest.should_reject_when_lower: tagged test is failing",
            "com.tradestore.service.TradeIngestionServiceTest.should_doSomething_when_untagged: no @Tag naming an acceptance criterion or assumption",
            "x.TypoTest.should_x_when_y: unknown tag US-101.9",
        ])

    def test_should_reportNothing_when_everyTestIsTaggedKnownAndPassing(self):
        reqs, backlog, tests, _, _ = _fixture(with_results=False)
        self.assertEqual(t.check(backlog, {"A-13"}, tests[:2], {}), [])


class RenderTest(unittest.TestCase):

    def test_should_summariseAndTraceRequirementsToTestedCriteria_when_rendered(self):
        doc = t.render(*_fixture(with_results=True))

        self.assertIn("Acceptance criteria with at least one test: **2 of 4 (50%)**", doc)
        self.assertIn("| R-03 | Version rule: reject a lower version. | E1 | US-101, US-104 | 2 of 3 |", doc)
        self.assertIn("### US-101: Store a new trade (E1): 1 of 2 criteria tested", doc)

    def test_should_linkTestsAndCodeAndShowStatus_when_criterionIsTested(self):
        doc = t.render(*_fixture(with_results=True))

        test_link = "[TradeIngestionServiceTest.should_storeTrade_when_new](../src/test/java/com/tradestore/service/TradeIngestionServiceTest.java)"
        code_links = ("[Trade](../src/main/java/com/tradestore/domain/Trade.java), "
                      "[TradeIngestionService](../src/main/java/com/tradestore/service/TradeIngestionService.java)")
        self.assertIn(f"| US-101.1 | `T1` v1 is stored | {test_link} | {code_links} | ✅ passed |", doc)
        self.assertIn("| US-101.2 | values are ignored | | | ⚠️ no test |", doc)
        self.assertIn("| US-104.1 | A `LowerVersionException` is raised |", doc)
        self.assertIn("❌ failed |", doc)

    def test_should_showNotRun_when_noResultsAreGiven(self):
        doc = t.render(*_fixture(with_results=False))

        self.assertIn("| ▫ not run |", doc)

    def test_should_listAssumptionsVerifiedByTests_when_testsAreTaggedWithThem(self):
        doc = t.render(*_fixture(with_results=False))

        self.assertIn("| A-13 | [TradeIngestionServiceTest.should_reject_when_lower]", doc)


if __name__ == "__main__":
    unittest.main()
