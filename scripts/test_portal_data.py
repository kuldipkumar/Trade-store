import unittest

import portal_data
import portal_status


class AnchorTest(unittest.TestCase):

    def test_builds_the_github_heading_anchor(self):
        self.assertEqual(portal_data.anchor("US-101: Store a new trade (M)"), "us-101-store-a-new-trade-m")

    def test_drops_backticks_and_punctuation_but_keeps_hyphens(self):
        self.assertEqual(portal_data.anchor("US-304: Business rejections are final on the `stream` (M)"),
                         "us-304-business-rejections-are-final-on-the-stream-m")


class BacklogTest(unittest.TestCase):

    BACKLOG = """
| Epic | Name | Stories | Main sources |
|------|------|---------|--------------|
| E1 | Trade versioning and storage | US-101 to US-102 | R-02 |

## E1: Trade versioning and storage

### US-101: Store a new trade (M)
*As a trade operations user, I want it stored, so that it is kept.*

| AC | Given | When | Then |
|----|-------|------|------|
| US-101.1 | a | b | c |
| US-101.2 | a | b | c |

### US-102: Keep the higher version (S)
*As a user, I want versions, so that order holds.*

| US-102.1 | a | b | c |
"""

    def test_reads_epics_with_their_stories_priority_and_criteria(self):
        epics = portal_data.parse_backlog(self.BACKLOG)

        self.assertEqual([e["id"] for e in epics], ["E1"])
        self.assertEqual(epics[0]["name"], "Trade versioning and storage")
        stories = epics[0]["stories"]
        self.assertEqual([s["id"] for s in stories], ["US-101", "US-102"])
        self.assertEqual(stories[0]["title"], "Store a new trade")
        self.assertEqual(stories[0]["priority"], "Must")
        self.assertEqual(stories[1]["priority"], "Should")
        self.assertEqual(stories[0]["criteria"], 2)
        self.assertEqual(stories[0]["anchor"], "us-101-store-a-new-trade-m")
        self.assertTrue(stories[0]["narrative"].startswith("As a trade operations user"))


class TrivyTest(unittest.TestCase):

    def test_sums_the_totals_of_every_scanned_target(self):
        text = ("pom.xml (pom)\nTotal: 3 (UNKNOWN: 0, LOW: 1, MEDIUM: 2, HIGH: 0, CRITICAL: 0)\n"
                "app.jar (jar)\nTotal: 2 (UNKNOWN: 0, LOW: 0, MEDIUM: 0, HIGH: 1, CRITICAL: 1)\n")

        self.assertEqual(portal_data.trivy_totals(text),
                         {"UNKNOWN": 0, "LOW": 1, "MEDIUM": 2, "HIGH": 1, "CRITICAL": 1, "total": 5})

    def test_reports_zero_when_nothing_was_found(self):
        self.assertEqual(portal_data.trivy_totals("")["total"], 0)


class TestKindTest(unittest.TestCase):

    def test_classifies_tests_by_suite(self):
        self.assertEqual(portal_data.test_kind("com/tradestore/it/PoisonPillIT.java"), "integration")
        self.assertEqual(portal_data.test_kind("com/tradestore/it/PerformanceBaselinePT.java"), "performance")
        self.assertEqual(portal_data.test_kind("com/tradestore/repo/CiPipelineConformanceTest.java"), "conformance")
        self.assertEqual(portal_data.test_kind("com/tradestore/service/TradeIngestionServiceTest.java"), "unit")

    def test_counts_test_methods_and_collects_tags(self):
        source = """
    @Test
    @Tag("US-101.1")
    void a() {}

    @ParameterizedTest
    @ValueSource(strings = {"x"})
    @Tag("US-101.2")
    @Tag("A-47")
    void b(String s) {}
"""
        count, tags = portal_data.count_tests(source)

        self.assertEqual(count, 2)
        self.assertEqual(tags, ["A-47", "US-101.1", "US-101.2"])


class StatusTest(unittest.TestCase):

    def test_keeps_the_newest_run_of_each_portal_workflow(self):
        runs = [{"name": "CI", "id": 3}, {"name": "Other", "id": 9}, {"name": "CI", "id": 2}, {"name": "CodeQL", "id": 1}]

        latest = portal_status.latest_per_workflow(runs)

        self.assertEqual({k: v["id"] for k, v in latest.items()}, {"CI": 3, "CodeQL": 1})


if __name__ == "__main__":
    unittest.main()
