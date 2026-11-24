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


def full_trace_id(trace_id):
    """Tempo's search drops leading zeros; W3C trace IDs are 32 hex characters."""
    return trace_id.rjust(32, "0")


def _attributes(span):
    return {a["key"]: next(iter(a["value"].values())) for a in span.get("attributes", [])}


def simplify_trace(tempo):
    """A Tempo trace (OTLP JSON) as a list of spans in start order, with depth and millisecond offsets."""
    raw = [span for batch in tempo.get("batches", tempo.get("resourceSpans", []))
           for scope in batch.get("scopeSpans", batch.get("instrumentationLibrarySpans", []))
           for span in scope.get("spans", [])]
    if not raw:
        return []
    by_id = {s["spanId"]: s for s in raw}
    t0 = min(int(s["startTimeUnixNano"]) for s in raw)

    def depth(span):
        d, parent = 0, span.get("parentSpanId")
        while parent in by_id:
            d, parent = d + 1, by_id[parent].get("parentSpanId")
        return d

    spans = []
    for s in sorted(raw, key=lambda x: int(x["startTimeUnixNano"])):
        attrs = _attributes(s)
        spans.append({"name": s["name"], "kind": s.get("kind", "").replace("SPAN_KIND_", ""), "depth": depth(s),
                      "startMs": round((int(s["startTimeUnixNano"]) - t0) / 1e6, 1),
                      "durationMs": round((int(s["endTimeUnixNano"]) - int(s["startTimeUnixNano"])) / 1e6, 2),
                      "db": attrs.get("db.system", ""), "tradeId": attrs.get("trade.id", ""),
                      "outcome": attrs.get("trade.outcome", "")})
    return spans


def get_json(url):
    with urllib.request.urlopen(url, timeout=20) as response:
        return json.load(response)


def sample_trace(tempo, loki, minutes):
    """The most recent trade trace that reached MongoDB, and its log lines from Loki."""
    found = get_json(f"{tempo}/api/search?" + urllib.parse.urlencode({"q": '{name="outbox.relay"}', "limit": 1}))
    if not found.get("traces"):
        return None
    trace_id = full_trace_id(found["traces"][0]["traceID"])
    spans = simplify_trace(get_json(f"{tempo}/api/traces/{trace_id}"))
    logs = []
    if loki:
        end = time.time()
        result = get_json(f"{loki}/loki/api/v1/query_range?" + urllib.parse.urlencode({
            "query": '{service_name="trade-store"} | trace_id="' + trace_id + '"',
            "start": int((end - minutes * 60) * 1e9), "end": int(end * 1e9), "limit": 20}))
        logs = sorted((v for stream in result["data"]["result"] for v in stream["values"]), key=lambda v: v[0])
        logs = [{"ts": int(v[0]) // 1_000_000, "line": v[1]} for v in logs]
    return {"traceId": trace_id, "spans": spans, "logs": logs}


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
        series, error = [], None
        for target in panel["targets"]:
            try:
                if panel["type"] == "stat":
                    for r in query(base, "query", {"query": target["expr"], "time": end}):
                        value = points([r["value"]])
                        series.append({"label": legend(target["legend"], r["metric"]), "value": value[0][1] if value else None})
                else:
                    for r in query(base, "query_range", {"query": target["expr"], "start": start, "end": end, "step": 5}):
                        series.append({"label": legend(target["legend"], r["metric"]), "points": points(r["values"])})
            except (OSError, ValueError, KeyError) as failed:   # URLError/HTTPError are OSErrors
                # One bad query leaves that panel empty; it never fails CI or blocks the portal
                error = f"{target['expr']}: {failed}"
                print(f"WARN panel '{panel['title']}' query failed: {error}")
        entry = {"title": panel["title"], "type": panel["type"], "unit": panel["unit"], "series": series}
        if error:
            entry["error"] = error
        result.append(entry)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prometheus", default="http://localhost:9090")
    parser.add_argument("--minutes", type=float, default=4)
    parser.add_argument("--out", default="observability-snapshot.json")
    parser.add_argument("--tempo", help="Tempo URL, to include one trade's trace (e.g. http://localhost:3200)")
    parser.add_argument("--loki", help="Loki URL, to include that trace's log lines (e.g. http://localhost:3100)")
    args = parser.parse_args()
    with open(DASHBOARD, encoding="utf-8") as f:
        dashboard = json.load(f)
    snapshot = {"takenAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "minutes": args.minutes, "dashboard": dashboard.get("title"), "panels": evaluate(args.prometheus, args.minutes, dashboard)}
    if args.tempo:
        try:
            snapshot["trace"] = sample_trace(args.tempo, args.loki, args.minutes)
        except (OSError, ValueError, KeyError) as failed:   # never blocks the snapshot
            print(f"WARN no sample trace: {failed}")
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(snapshot, f)
    filled = sum(1 for p in snapshot["panels"] if p["series"])
    print(f"Wrote {args.out}: {filled} of {len(snapshot['panels'])} panels have data")


if __name__ == "__main__":
    main()
