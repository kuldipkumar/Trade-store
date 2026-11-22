"""DAST gate (A-47): fail when the OWASP ZAP report has any High risk alert; print a summary by risk.

Usage: python3 scripts/zap_gate.py zap/zap-report.json
"""
import json
import sys

RISKS = {"3": "High", "2": "Medium", "1": "Low", "0": "Informational"}


def summarise(report):
    """Counts alerts (distinct rules) per risk level across every scanned site."""
    counts = {name: 0 for name in RISKS.values()}
    titles = {name: [] for name in RISKS.values()}
    for site in report.get("site", []):
        for alert in site.get("alerts", []):
            risk = RISKS.get(str(alert.get("riskcode")), "Informational")
            counts[risk] += 1
            titles[risk].append(alert.get("name", alert.get("alert", "?")))
    return counts, titles


def main(path):
    with open(path, encoding="utf-8") as f:
        counts, titles = summarise(json.load(f))
    print("ZAP alerts by risk: " + ", ".join(f"{k}: {v}" for k, v in counts.items()))
    for name in titles["High"] + titles["Medium"]:
        print(f"  - {name}")
    if counts["High"]:
        print("DAST gate failed: High risk alerts found", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
