"""Snapshots the pipeline status from the public GitHub API into portal/data/status.json.

The portal reads the same API live; this snapshot is its fallback where the live call is not possible
(rate limit, or a host that blocks it). Usage: python3 scripts/portal_status.py --out portal/data/status.json
"""
import argparse
import datetime
import http.client
import json
import os
import time
import urllib.request

REPO = "kuldipkumar/Trade-store"
WORKFLOWS = ["CI", "CodeQL", "Portal", "Performance baseline"]


def get(path):
    request = urllib.request.Request(f"https://api.github.com/repos/{REPO}/{path}",
                                     headers={"Accept": "application/vnd.github+json"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    for attempt in range(3):  # the connection is sometimes dropped mid-response
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)
        except (OSError, http.client.HTTPException, ValueError):
            if attempt == 2:
                raise
            time.sleep(2 * (attempt + 1))


def latest_per_workflow(runs):
    """The newest run of each portal workflow, in the API's newest-first order."""
    latest = {}
    for run in runs:
        if run["name"] in WORKFLOWS and run["name"] not in latest:
            latest[run["name"]] = run
    return latest


def slim(run):
    keys = ["id", "name", "status", "conclusion", "html_url", "head_sha", "created_at", "updated_at", "event"]
    return {k: run.get(k) for k in keys}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="portal/data/status.json")
    args = parser.parse_args()
    runs = get("actions/runs?branch=main&per_page=50")["workflow_runs"]
    latest = latest_per_workflow(runs)
    jobs = get(f"actions/runs/{latest['CI']['id']}/jobs")["jobs"] if "CI" in latest else []
    status = {
        "fetchedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "runs": {name: slim(run) for name, run in latest.items()},
        "jobs": [{"name": j["name"], "status": j["status"], "conclusion": j["conclusion"], "html_url": j["html_url"],
                  "steps": [{"name": s["name"], "status": s["status"], "conclusion": s["conclusion"]} for s in j["steps"]]}
                 for j in jobs],
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(status, f, indent=1)
    summary = ", ".join(name + "=" + str(run["conclusion"]) for name, run in status["runs"].items())
    print("Wrote " + args.out + ": " + summary)


if __name__ == "__main__":
    main()
