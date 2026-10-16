"""python scripts/dgx/test_paa_policy.py — the shared GPU budget (both projects together)."""
import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from paa_policy import DEFAULT_LIMITS, Job, active_rule, decide

IST = ZoneInfo("Asia/Kolkata")
DAY = active_rule(datetime(2026, 10, 16, 11, 0, tzinfo=IST), DEFAULT_LIMITS)
NIGHT = active_rule(datetime(2026, 10, 16, 23, 0, tzinfo=IST), DEFAULT_LIMITS)


def mem(used: set, others: set = frozenset()):
    """8 cards; `used` = ours (busy), `others` = other users (busy), rest idle."""
    return {i: (20000.0 if i in used or i in others else 5.0) for i in range(8)}


class Rules(unittest.TestCase):
    def test_day_and_night_windows(self):
        self.assertEqual((DAY["mode"], DAY["cap"], DAY["keep_free"], DAY["floor"]), ("day", 2, 0, 2))
        self.assertEqual((NIGHT["mode"], NIGHT["cap"], NIGHT["keep_free"], NIGHT["floor"]), ("night", 4, 1, 2))
        self.assertEqual(active_rule(datetime(2026, 10, 16, 19, 0, tzinfo=IST), DEFAULT_LIMITS)["mode"], "night")
        self.assertEqual(active_rule(datetime(2026, 10, 17, 6, 59, tzinfo=IST), DEFAULT_LIMITS)["mode"], "night")
        self.assertEqual(active_rule(datetime(2026, 10, 17, 7, 0, tzinfo=IST), DEFAULT_LIMITS)["mode"], "day")


class Budget(unittest.TestCase):
    def test_day_empty_box_two_cards(self):
        self.assertEqual(decide(DAY, mem(set()), set(), []), (2, []))

    def test_day_localai_has_two_paa_gets_none(self):
        self.assertEqual(decide(DAY, mem({0, 1}), {0, 1}, []), (0, []))

    def test_day_localai_one_paa_one(self):
        self.assertEqual(decide(DAY, mem({0}), {0}, []), (1, []))

    def test_morning_over_total_paa_gives_back_holding_first(self):
        # 07:00: LocalAI 0,1 + PAA 6 (training) + PAA 7 (holding) = 4 > 2 -> pause both PAA, holding first
        jobs = [Job("chain-1b", [6], started=100, holding=False), Job("chain-270m", [7], started=200, holding=True)]
        allow, pre = decide(DAY, mem({0, 1, 6, 7}), {0, 1, 6, 7}, jobs)
        self.assertEqual((allow, pre), (0, ["chain-270m", "chain-1b"]))

    def test_over_by_one_pauses_only_one_newest(self):
        jobs = [Job("old", [6], 100, False), Job("new", [7], 200, False)]
        self.assertEqual(decide(DAY, mem({0, 6, 7}), {0, 6, 7}, jobs), (0, ["new"]))

    def test_night_keeps_one_idle(self):
        # LocalAI 2 cards, others use 3, idle 3 -> may take 2 (cap 4), leaving 1 idle
        self.assertEqual(decide(NIGHT, mem({0, 1}, {2, 3, 4}), {0, 1}, []), (2, []))

    def test_night_last_idle_card_is_not_taken(self):
        self.assertEqual(decide(NIGHT, mem({0, 1, 2}, {3, 4, 5, 6}), {0, 1, 2}, []), (0, []))

    def test_night_floor_two_always_allowed(self):
        # only 1 idle card but we have 1 < floor 2 -> may still take it
        self.assertEqual(decide(NIGHT, mem({0}, {1, 2, 3, 4, 5, 6}), {0}, []), (1, []))

    def test_night_someone_took_last_card_give_one_back_above_floor(self):
        jobs = [Job("paa", [2], 100, False)]
        allow, pre = decide(NIGHT, mem({0, 1, 2}, {3, 4, 5, 6, 7}), {0, 1, 2}, jobs)
        self.assertEqual((allow, pre), (0, ["paa"]))

    def test_night_at_floor_nothing_paused(self):
        jobs = [Job("paa", [1], 100, False)]
        self.assertEqual(decide(NIGHT, mem({0, 1}, {2, 3, 4, 5, 6, 7}), {0, 1}, jobs), (0, []))

    def test_hold_rule_launches_nothing(self):
        self.assertEqual(decide({**DAY, "hold": True}, mem(set()), set(), [])[0], 0)

    def test_job_already_pausing_is_not_asked_twice_and_counts_as_freeing(self):
        jobs = [Job("saving", [7], 100, False, pausing=True), Job("other", [6], 200, False)]
        # LocalAI 0,1 + PAA 6,7 = 4 > 2, but 7 is already freeing -> only one more to give back
        self.assertEqual(decide(DAY, mem({0, 1, 6, 7}), {0, 1, 6, 7}, jobs), (0, ["other"]))

    def test_localai_alone_over_cap_paa_has_nothing_to_give(self):
        self.assertEqual(decide(DAY, mem({0, 1, 2}), {0, 1, 2}, []), (0, []))


if __name__ == "__main__":
    unittest.main(verbosity=1)
