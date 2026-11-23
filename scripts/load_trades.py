"""Streams loadtest/trades-10k.csv into a running Trade Store, over Kafka (default) or REST (US-605, demos).

Kafka: messages are keyed by tradeId and written through the broker container's console producer, so no Python
Kafka library is needed. Trade IDs get a per-run prefix, so every run starts fresh and each row's expected outcome
holds. Examples:
    python3 scripts/load_trades.py                          # all 10,000 rows to Kafka at 500 messages/s
    python3 scripts/load_trades.py --rate 50 --limit 1500   # a gentler demo
    python3 scripts/load_trades.py --channel rest --rate 50 --limit 3000
"""
import argparse
import collections
import csv
import datetime
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REST_STATUS = {("ACCEPTED", ""): 201, ("REPLACED", ""): 200, ("REJECTED", "LOWER_VERSION"): 409,
               ("REJECTED", "MATURITY_IN_PAST"): 422, ("REJECTED", "INVALID_FIELD"): 400, ("POISON", ""): 400}


def read_rows(path, limit=None):
    with open(path, newline="", encoding="utf-8") as f:
        for i, row in enumerate(csv.DictReader(f)):
            if limit is not None and i >= limit:
                return
            yield row


def default_prefix():
    """Unique per run, even for runs started in the same second (date, time and a random suffix)."""
    return "L" + datetime.datetime.now().strftime("%y%m%d%H%M%S") + secrets.token_hex(2) + "-"


def to_message(row, prefix):
    """(key, value): the prefixed tradeId, and the trade as compact JSON, or the raw text for a poison pill."""
    key = prefix + row["tradeId"]
    if row["expectedOutcome"] == "POISON":
        return key, row["raw"]
    trade = {"tradeId": key, "version": int(row["version"]), "counterPartyId": row["counterPartyId"],
             "bookId": row["bookId"], "maturityDate": row["maturityDate"]}
    return key, json.dumps(trade, separators=(",", ":"))


def expected_status(row):
    return REST_STATUS[(row["expectedOutcome"], row["expectedReason"])]


class Pacer:
    """Spaces calls evenly at `rate` per second (0 = as fast as possible), catching up after slow calls."""

    def __init__(self, rate, now=time.monotonic, sleep=time.sleep):
        self.interval = 1.0 / rate if rate > 0 else 0.0
        self.now, self.sleep, self.next_at = now, sleep, None

    def wait(self):
        if not self.interval:
            return
        t = self.now()
        if self.next_at is None:
            self.next_at = t
        elif self.next_at > t:
            self.sleep(self.next_at - t)
        self.next_at += self.interval


def send_stream(rows, prefix, rate, service, topic):
    producer = subprocess.Popen(
        ["docker", "compose", "exec", "-T", service, "/opt/kafka/bin/kafka-console-producer.sh",
         "--bootstrap-server", "localhost:9092", "--topic", topic,
         "--property", "parse.key=true", "--property", "key.separator=|"],
        stdin=subprocess.PIPE, text=True, cwd=ROOT)
    pacer, sent, last_flush = Pacer(rate), collections.Counter(), time.monotonic()
    try:
        for row in rows:
            pacer.wait()
            key, value = to_message(row, prefix)
            producer.stdin.write(f"{key}|{value}\n")
            sent[(row["expectedOutcome"], row["expectedReason"])] += 1
            if time.monotonic() - last_flush > 0.2:
                producer.stdin.flush()
                last_flush = time.monotonic()
        producer.stdin.close()
    except (BrokenPipeError, OSError):
        producer.kill()
        raise SystemExit("The Kafka console producer stopped; is the stack up (docker compose up -d)?")
    if producer.wait() != 0:
        raise SystemExit("The Kafka console producer failed; is the stack up (docker compose up -d)?")
    return sent, collections.Counter()


def send_rest(rows, prefix, rate, api):
    pacer, sent, mismatches = Pacer(rate), collections.Counter(), collections.Counter()
    for row in rows:
        pacer.wait()
        _, value = to_message(row, prefix)
        request = urllib.request.Request(api.rstrip("/") + "/trades", data=value.encode(), method="POST",
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
        sent[(row["expectedOutcome"], row["expectedReason"])] += 1
        if status != expected_status(row):
            mismatches[f"{row['expectedOutcome']} {row['expectedReason']} expected {expected_status(row)} got {status}".strip()] += 1
    return sent, mismatches


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--file", default=os.path.join(ROOT, "loadtest", "trades-10k.csv"))
    parser.add_argument("--channel", choices=["stream", "rest"], default="stream")
    parser.add_argument("--rate", type=float, default=None, help="messages per second (default 500 stream, 50 REST; 0 = no limit)")
    parser.add_argument("--limit", type=int, default=None, help="send only the first N rows")
    parser.add_argument("--prefix", default=None, help="prefix for trade IDs (default: L<timestamp>-)")
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--service", default="kafka", help="docker compose service of the broker")
    parser.add_argument("--topic", default="trades.inbound")
    args = parser.parse_args()
    rate = args.rate if args.rate is not None else (500 if args.channel == "stream" else 50)
    prefix = args.prefix or default_prefix()
    started = time.monotonic()
    rows = read_rows(args.file, args.limit)
    if args.channel == "stream":
        sent, mismatches = send_stream(rows, prefix, rate, args.service, args.topic)
    else:
        sent, mismatches = send_rest(rows, prefix, rate, args.api)
    elapsed = time.monotonic() - started
    total = sum(sent.values())
    print(f"Sent {total} messages over {args.channel} in {elapsed:.1f} s ({total / elapsed if elapsed else 0:.0f}/s), prefix {prefix}")
    for (outcome, reason), n in sorted(sent.items()):
        print(f"  expected {outcome:<9} {reason:<17} {n:>6}")
    if args.channel == "stream":
        print(f"Check: curl '{args.api}/reports/audit?tradeId={prefix}T00001' or the Grafana dashboard")
    if mismatches:
        print("Unexpected responses:")
        for text, n in mismatches.most_common():
            print(f"  {n:>6}  {text}")
        sys.exit(1)


if __name__ == "__main__":
    main()
