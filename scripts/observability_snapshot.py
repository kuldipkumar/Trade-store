"""Evaluates every panel of the Grafana dashboard against Prometheus and saves the results (US-604.2).

CI runs it after a demo load, so the published portal shows the dashboard's charts from a real run of the stack,
with exactly the queries Grafana uses. Usage:
    python3 scripts/observability_snapshot.py --prometheus http://localhost:9090 --minutes 4 --out observability-snapshot.json
"""
import argparse
import datetime
import json
import math
import re
import time
import urllib.parse
import urllib.request

DASHBOARD = "observability/grafana/dashboards/trade-store.json"


def legend(fmt, labels):
    """A series name from Grafana's legendFormat ({{label}} placeholders), or the labels themselves."""
    if fmt:
        return re.sub(r"\{\{\s*(\w+)\s*\}\}", lambda m: labels.get(m.group(1), ""), fmt)
    return ", ".join(f"{k}={v}" for k, v in sorted(labels.items()) if k != "__name__") or "value"


def panels(dashboard):
    """Stat and time-series panels with their queries, in dashboard order (rows are layout only)."""
    found = []
    for panel in dashboard.get("panels", []):
        if panel.get("type") not in ("stat", "timeseries"):
            continue
        found.append({"title": panel["title"], "type": panel["type"],
                      "unit": panel.get("fieldConfig", {}).get("defaults", {}).get("unit", "short"),
                      "targets": [{"expr": t["expr"], "legend": t.get("legendFormat", "")} for t in panel.get("targets", [])]})
    return found


def points(values):
    """Prometheus [time, "value"] pairs as numbers, dropping NaN and infinities (e.g. a quantile with no traffic)."""
    kept = []
    for t, v in values:
        number = float(v)
        if math.isfinite(number):
            kept.append([t, round(number, 6)])
    return kept


def query(base, path, params):
    url = f"{base}/api/v1/{path}?{urllib.parse.urlencode(params)}"
    with urllib.request.urlopen(url, timeout=20) as response:
        body = json.load(response)
    return body["data"]["result"] if body.get("status") == "success" else []


def evaluate(base, minutes, dashboard):
    end = time.time()
    start = end - minutes * 60
    result = []
    for panel in panels(dashboard):
        series = []
        for target in panel["targets"]:
            if panel["type"] == "stat":
                for r in query(base, "query", {"query": target["expr"], "time": end}):
                    value = points([r["value"]])
                    series.append({"label": legend(target["legend"], r["metric"]), "value": value[0][1] if value else None})
            else:
                for r in query(base, "query_range", {"query": target["expr"], "start": start, "end": end, "step": 5}):
                    series.append({"label": legend(target["legend"], r["metric"]), "points": points(r["values"])})
        result.append({"title": panel["title"], "type": panel["type"], "unit": panel["unit"], "series": series})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prometheus", default="http://localhost:9090")
    parser.add_argument("--minutes", type=float, default=4)
    parser.add_argument("--out", default="observability-snapshot.json")
    args = parser.parse_args()
    with open(DASHBOARD, encoding="utf-8") as f:
        dashboard = json.load(f)
    snapshot = {"takenAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "minutes": args.minutes, "dashboard": dashboard.get("title"), "panels": evaluate(args.prometheus, args.minutes, dashboard)}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(snapshot, f)
    filled = sum(1 for p in snapshot["panels"] if p["series"])
    print(f"Wrote {args.out}: {filled} of {len(snapshot['panels'])} panels have data")


if __name__ == "__main__":
    main()
