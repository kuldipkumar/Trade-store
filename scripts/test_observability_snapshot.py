"""US-604.2: the portal's dashboard snapshot (scripts/observability_snapshot.py)."""
import unittest
import urllib.error
from unittest import mock

import observability_snapshot


class SnapshotTest(unittest.TestCase):

    def test_should_fillTheGrafanaLegendPlaceholders_when_aLegendFormatIsSet(self):
        self.assertEqual(observability_snapshot.legend("{{outcome}} via {{channel}}", {"outcome": "REJECTED", "channel": "REST"}),
                         "REJECTED via REST")

    def test_should_nameTheSeriesByItsLabels_when_noLegendFormatIsSet(self):
        self.assertEqual(observability_snapshot.legend("", {"outcome": "ACCEPTED"}), "outcome=ACCEPTED")

    def test_should_takeTheQueriesFromStatAndTimeSeriesPanels_when_rowsAreMixedIn(self):
        dashboard = {"panels": [
            {"type": "row", "title": "Business"},
            {"type": "stat", "title": "Stored", "fieldConfig": {"defaults": {"unit": "short"}}, "targets": [{"expr": "a"}]},
            {"type": "timeseries", "title": "Outcomes", "fieldConfig": {"defaults": {"unit": "s"}},
             "targets": [{"expr": "b", "legendFormat": "{{outcome}}"}]}]}

        panels = observability_snapshot.panels(dashboard)

        self.assertEqual([(p["title"], p["type"], p["unit"]) for p in panels],
                         [("Stored", "stat", "short"), ("Outcomes", "timeseries", "s")])
        self.assertEqual(panels[1]["targets"], [{"expr": "b", "legend": "{{outcome}}"}])

    def test_should_dropValuesThatAreNotNumbers_when_prometheusReturnsNanOrInfinity(self):
        self.assertEqual(observability_snapshot.points([[1, "2.5"], [2, "NaN"], [3, "+Inf"]]), [[1, 2.5]])

    def test_should_recordTheErrorAndKeepGoing_when_onePanelQueryFails(self):
        # Code review of PR #70: one bad query must not fail CI and block the portal
        dashboard = {"panels": [
            {"type": "stat", "title": "Broken", "targets": [{"expr": "bad"}]},
            {"type": "stat", "title": "Fine", "targets": [{"expr": "good"}]}]}

        def fake_query(base, path, params):
            if params["query"] == "bad":
                raise urllib.error.HTTPError("u", 422, "execution error", None, None)
            return [{"metric": {}, "value": [1, "3"]}]

        with mock.patch.object(observability_snapshot, "query", side_effect=fake_query):
            result = observability_snapshot.evaluate("http://prometheus", 3, dashboard)

        self.assertEqual(result[0]["series"], [])
        self.assertIn("422", result[0]["error"])
        self.assertEqual(result[1]["series"], [{"label": "value", "value": 3.0}])


if __name__ == "__main__":
    unittest.main()
