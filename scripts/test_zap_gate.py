import json
import os
import tempfile
import unittest

import zap_gate


def report(*riskcodes):
    return {"site": [{"alerts": [{"riskcode": str(r), "name": f"rule-{i}"} for i, r in enumerate(riskcodes)]}]}


class ZapGateTest(unittest.TestCase):

    def test_counts_alerts_per_risk_level(self):
        counts, titles = zap_gate.summarise(report(3, 2, 2, 0))
        self.assertEqual(counts, {"High": 1, "Medium": 2, "Low": 0, "Informational": 1})
        self.assertEqual(titles["High"], ["rule-0"])

    def _gate(self, data):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(data, f)
        try:
            return zap_gate.main(f.name)
        finally:
            os.unlink(f.name)

    def test_fails_when_a_high_risk_alert_exists(self):
        self.assertEqual(self._gate(report(1, 3)), 1)

    def test_passes_when_only_medium_or_lower_alerts_exist(self):
        self.assertEqual(self._gate(report(2, 1, 0)), 0)

    def test_passes_when_the_report_is_empty(self):
        self.assertEqual(self._gate({"site": []}), 0)


if __name__ == "__main__":
    unittest.main()
