"""Generates loadtest/trades-10k.csv: 10,000 trade messages with a known mix of outcomes (US-605).

Each trade's rows are in version order and trades are interleaved at random, so the file behaves like a real stream
keyed by tradeId. Every row records the outcome the store must produce. Deterministic for a given seed.
Usage: python3 scripts/generate_trades.py [--seed 42] [--out loadtest/trades-10k.csv]
"""
import argparse
import csv
import datetime
import random

FIELDS = ["seq", "tradeId", "version", "counterPartyId", "bookId", "maturityDate", "expectedOutcome", "expectedReason", "raw"]
NEW, HIGHER, SAME, LOWER, PAST, INVALID, POISON = 6000, 1500, 800, 800, 600, 200, 100


def _date(rng, start, end):
    first, last = datetime.date.fromisoformat(start), datetime.date.fromisoformat(end)
    return (first + datetime.timedelta(days=rng.randrange((last - first).days + 1))).isoformat()


def _trade(rng, tid, version, maturity, book=None):
    return {"tradeId": tid, "version": str(version), "counterPartyId": f"CP-{rng.randint(1, 50)}",
            "bookId": f"B{rng.randint(1, 20)}" if book is None else book, "maturityDate": maturity, "raw": ""}


def generate(seed=42):
    rng = random.Random(seed)
    streams = []   # one list of rows per key, in the order they must arrive
    base = [f"T{i:05d}" for i in range(1, NEW + 1)]
    higher = set(rng.sample(base, HIGHER))
    lower = set(rng.sample(sorted(higher), LOWER))
    same = set(rng.sample(base, SAME))
    for tid in base:
        maturity = _date(rng, "2028-01-01", "2032-12-31")
        rows = [dict(_trade(rng, tid, 1, maturity), expectedOutcome="ACCEPTED", expectedReason="")]
        current = 1
        if tid in higher:
            current = 2
            rows.append(dict(_trade(rng, tid, 2, maturity), expectedOutcome="REPLACED", expectedReason=""))
        if tid in same:
            rows.append(dict(_trade(rng, tid, current, maturity), expectedOutcome="REPLACED", expectedReason=""))
        if tid in lower:
            rows.append(dict(_trade(rng, tid, 1, maturity), expectedOutcome="REJECTED", expectedReason="LOWER_VERSION"))
        streams.append(rows)
    for i in range(NEW + 1, NEW + PAST + 1):
        streams.append([dict(_trade(rng, f"T{i:05d}", 1, _date(rng, "2012-01-01", "2025-12-31")),
                             expectedOutcome="REJECTED", expectedReason="MATURITY_IN_PAST")])
    for i in range(NEW + PAST + 1, NEW + PAST + INVALID + 1):
        streams.append([dict(_trade(rng, f"T{i:05d}", 1, _date(rng, "2028-01-01", "2032-12-31"), book=""),
                             expectedOutcome="REJECTED", expectedReason="INVALID_FIELD")])
    for i in range(1, POISON + 1):
        streams.append([{"tradeId": f"P{i:05d}", "version": "", "counterPartyId": "", "bookId": "", "maturityDate": "",
                         "raw": f"not a trade P{i:05d}", "expectedOutcome": "POISON", "expectedReason": ""}])
    # Interleave: repeatedly take the next row of a random key, weighted by what each key has left
    rows, pending = [], [s for s in streams]
    weights = [len(s) for s in pending]
    while pending:
        k = rng.choices(range(len(pending)), weights=weights)[0]
        rows.append(pending[k].pop(0))
        weights[k] -= 1
        if not pending[k]:
            pending.pop(k)
            weights.pop(k)
    return [dict(r, seq=str(i)) for i, r in enumerate(rows, 1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", default="loadtest/trades-10k.csv")
    args = parser.parse_args()
    rows = generate(args.seed)
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows({k: r[k] for k in FIELDS} for r in rows)
    print(f"Wrote {args.out}: {len(rows)} rows")


if __name__ == "__main__":
    main()
