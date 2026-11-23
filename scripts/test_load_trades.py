"""US-605: the 10,000-trade load file and the script that streams it (scripts/generate_trades.py, scripts/load_trades.py)."""
import collections
import datetime
import unittest

import generate_trades
import load_trades


class GenerateTest(unittest.TestCase):

    def setUp(self):
        self.rows = generate_trades.generate(seed=42)

    def test_should_produceTenThousandRowsWithTheAgreedMix_when_generated(self):
        mix = collections.Counter((r["expectedOutcome"], r["expectedReason"]) for r in self.rows)

        self.assertEqual(len(self.rows), 10000)
        self.assertEqual(mix, {("ACCEPTED", ""): 6000, ("REPLACED", ""): 2300, ("REJECTED", "LOWER_VERSION"): 800,
                               ("REJECTED", "MATURITY_IN_PAST"): 600, ("REJECTED", "INVALID_FIELD"): 200, ("POISON", ""): 100})

    def test_should_beIdentical_when_generatedTwiceWithTheSameSeed(self):
        self.assertEqual(generate_trades.generate(seed=42), self.rows)

    def test_should_matchTheStoreRules_when_eachTradesRowsAreReplayedInFileOrder(self):
        # Replays the file through the business rules: the expected outcome of every row must hold
        stored = {}
        for r in self.rows:
            if r["expectedOutcome"] == "POISON":
                continue
            tid, version = r["tradeId"], int(r["version"])
            if not r["bookId"]:
                expected = ("REJECTED", "INVALID_FIELD")
            elif tid in stored and version < stored[tid]:
                expected = ("REJECTED", "LOWER_VERSION")
            elif r["maturityDate"] < datetime.date.today().isoformat():   # the real today: the file must stay valid
                expected = ("REJECTED", "MATURITY_IN_PAST")
            else:
                expected = ("REPLACED" if tid in stored else "ACCEPTED", "")
                stored[tid] = version
            self.assertEqual((r["expectedOutcome"], r["expectedReason"]), expected, r)

    def test_should_neverUseTheKeySeparatorOrLineBreaks_when_valuesAreWritten(self):
        for r in self.rows:
            for value in r.values():
                self.assertNotIn("|", value)
                self.assertNotIn("\n", value)


    def test_should_keepEveryStoredTradesMaturityFarInTheFuture_when_generated(self):
        # Code review of PR #71: dates from 2028 would expire and silently change the expected outcomes
        stored = [r["maturityDate"] for r in self.rows if r["expectedOutcome"] in ("ACCEPTED", "REPLACED")]
        self.assertGreaterEqual(min(stored), "2090-01-01")


class MessageTest(unittest.TestCase):

    ROW = {"seq": "1", "tradeId": "T00001", "version": "2", "counterPartyId": "CP-7", "bookId": "B3",
           "maturityDate": "2029-05-20", "expectedOutcome": "REPLACED", "expectedReason": "", "raw": ""}

    def test_should_buildAKeyedJsonTradeWithThePrefixedId_when_aRowIsConverted(self):
        key, value = load_trades.to_message(self.ROW, "R1-")

        self.assertEqual(key, "R1-T00001")
        self.assertEqual(value, '{"tradeId":"R1-T00001","version":2,"counterPartyId":"CP-7","bookId":"B3","maturityDate":"2029-05-20"}')

    def test_should_sendTheRawTextUnchanged_when_theRowIsAPoisonPill(self):
        row = dict(self.ROW, expectedOutcome="POISON", raw="not a trade T00001")

        self.assertEqual(load_trades.to_message(row, "R1-"), ("R1-T00001", "not a trade T00001"))

    def test_should_expectTheRestStatusOfEachOutcome_when_sentOverRest(self):
        cases = {("ACCEPTED", ""): 201, ("REPLACED", ""): 200, ("REJECTED", "LOWER_VERSION"): 409,
                 ("REJECTED", "MATURITY_IN_PAST"): 422, ("REJECTED", "INVALID_FIELD"): 400, ("POISON", ""): 400}
        for (outcome, reason), status in cases.items():
            self.assertEqual(load_trades.expected_status(dict(self.ROW, expectedOutcome=outcome, expectedReason=reason)), status)


class PrefixTest(unittest.TestCase):

    def test_should_giveEveryRunItsOwnTradeIds_when_runsStartInTheSameSecond(self):
        # Code review of PR #71
        prefixes = {load_trades.default_prefix() for _ in range(50)}
        self.assertEqual(len(prefixes), 50)


class PacerTest(unittest.TestCase):

    def test_should_spaceSendsEvenly_when_aRateIsSet(self):
        clock = [100.0]
        slept = []
        pacer = load_trades.Pacer(rate=4, now=lambda: clock[0], sleep=lambda s: (slept.append(round(s, 3)), clock.__setitem__(0, clock[0] + s)))

        for _ in range(4):
            pacer.wait()

        self.assertEqual(slept, [0.25, 0.25, 0.25])

    def test_should_notSleep_when_theRateIsUnlimited(self):
        slept = []
        pacer = load_trades.Pacer(rate=0, now=lambda: 0.0, sleep=slept.append)
        for _ in range(3):
            pacer.wait()
        self.assertEqual(slept, [])


if __name__ == "__main__":
    unittest.main()
