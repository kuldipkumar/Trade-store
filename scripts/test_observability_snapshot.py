import unittest

import observability_snapshot


class SnapshotTest(unittest.TestCase):

    def test_names_a_series_from_the_grafana_legend_format(self):
        self.assertEqual(observability_snapshot.legend("{{outcome}} via {{channel}}", {"outcome": "REJECTED", "channel": "REST"}),
                         "REJECTED via REST")
        self.assertEqual(observability_snapshot.legend("", {"outcome": "ACCEPTED"}), "outcome=ACCEPTED")

    def test_takes_the_queries_from_the_dashboard_panels_and_skips_rows(self):
        dashboard = {"panels": [
            {"type": "row", "title": "Business"},
            {"type": "stat", "title": "Stored", "fieldConfig": {"defaults": {"unit": "short"}}, "targets": [{"expr": "a"}]},
            {"type": "timeseries", "title": "Outcomes", "fieldConfig": {"defaults": {"unit": "s"}},
             "targets": [{"expr": "b", "legendFormat": "{{outcome}}"}]}]}

        panels = observability_snapshot.panels(dashboard)

        self.assertEqual([(p["title"], p["type"], p["unit"]) for p in panels],
                         [("Stored", "stat", "short"), ("Outcomes", "timeseries", "s")])
        self.assertEqual(panels[1]["targets"], [{"expr": "b", "legend": "{{outcome}}"}])

    def test_drops_values_that_are_not_numbers(self):
        self.assertEqual(observability_snapshot.points([[1, "2.5"], [2, "NaN"], [3, "+Inf"]]), [[1, 2.5]])


if __name__ == "__main__":
    unittest.main()
