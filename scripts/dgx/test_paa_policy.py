"""python scripts/dgx/test_paa_policy.py — the shared GPU budget (both projects together) and PAA's share in it."""
import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from paa_policy import DEFAULT_LIMITS, Job, active_rule, decide, paa_reserved

IST = ZoneInfo("Asia/Kolkata")
DAY = active_rule(datetime(2026, 10, 16, 11, 0, tzinfo=IST), DEFAULT_LIMITS)
NIGHT = active_rule(datetime(2026, 10, 16, 23, 0, tzinfo=IST), DEFAULT_LIMITS)


def mem(used: set, others=frozenset()):
    """8 cards; `used` = ours (busy, both projects), `others` = other users (busy), rest idle."""
    return {i: (20000.0 if i in used or i in others else 5.0) for i in range(8)}


class Rules(unittest.TestCase):
    def test_day_and_night_windows(self):
        self.assertEqual((DAY["mode"], DAY["cap"], DAY["keep_free"], DAY["floor"]), ("day", 2, 0, 2))
        self.assertEqual((NIGHT["mode"], NIGHT["cap"], NIGHT["keep_free"], NIGHT["floor"]), ("night", 4, 1, 2))
        self.assertEqual(active_rule(datetime(2026, 10, 16, 19, 0, tzinfo=IST), DEFAULT_LIMITS)["mode"], "night")
        self.assertEqual(active_rule(datetime(2026, 10, 17, 6, 59, tzinfo=IST), DEFAULT_LIMITS)["mode"], "night")
        self.assertEqual(active_rule(datetime(2026, 10, 17, 7, 0, tzinfo=IST), DEFAULT_LIMITS)["mode"], "day")

    def test_paa_share_defaults_and_override(self):
        self.assertEqual((DAY["paa_min"], DAY["paa_max"]), (1, 1))
        self.assertEqual((NIGHT["paa_min"], NIGHT["paa_max"]), (1, 2))
        lim = {**DEFAULT_LIMITS, "paa": {"min": 0, "max": 2}}
        self.assertEqual(active_rule(datetime(2026, 10, 16, 11, 0, tzinfo=IST), lim)["paa_max"], 2)

    def test_reserved_formula(self):
        self.assertEqual(paa_reserved(NIGHT, using=0, wanting=0), 0)   # no work: nothing reserved
        self.assertEqual(paa_reserved(NIGHT, using=0, wanting=2), 1)   # guaranteed 1 when it has work
        self.assertEqual(paa_reserved(NIGHT, using=2, wanting=0), 2)   # what it uses (up to max)
        self.assertEqual(paa_reserved(DAY, using=0, wanting=3), 1)


class BeforeLocalAIReserves(unittest.TestCase):
    """LocalAI not updated yet: PAA only takes leftovers (and never more than its max)."""

    def test_day_empty_box_one_card(self):
        self.assertEqual(decide(DAY, mem(set()), set(), []), (1, []))

    def test_day_localai_has_two_paa_gets_none(self):
        self.assertEqual(decide(DAY, mem({0, 1}), {0, 1}, [], wanting=1), (0, []))

    def test_morning_over_total_paa_gives_back_holding_first(self):
        jobs = [Job("chain-1b", [6], started=100, holding=False), Job("chain-270m", [7], started=200, holding=True)]
        self.assertEqual(decide(DAY, mem({0, 1, 6, 7}), {0, 1, 6, 7}, jobs), (0, ["chain-270m", "chain-1b"]))

    def test_night_keeps_one_idle_and_max_two(self):
        self.assertEqual(decide(NIGHT, mem({0, 1}, {2, 3, 4}), {0, 1}, []), (2, []))
        self.assertEqual(decide(NIGHT, mem({0}), {0}, []), (2, []))       # room for 3 but PAA max is 2

    def test_night_last_idle_card_is_not_taken(self):
        self.assertEqual(decide(NIGHT, mem({0, 1, 2}, {3, 4, 5, 6}), {0, 1, 2}, []), (0, []))

    def test_job_already_pausing_counts_as_freeing(self):
        jobs = [Job("saving", [7], 100, False, pausing=True), Job("other", [6], 200, False)]
        self.assertEqual(decide(DAY, mem({0, 1, 6, 7}), {0, 1, 6, 7}, jobs), (0, ["other"]))


class LocalAIReservesShare(unittest.TestCase):
    """LocalAI honours PAA's share: PAA's guaranteed card even when LocalAI fills the total."""

    def test_day_localai_full_paa_still_gets_its_one(self):
        # LocalAI on 0,1 (= day cap 2), PAA has work -> PAA may take 1; LocalAI pauses one of its runs
        self.assertEqual(decide(DAY, mem({0, 1}), {0, 1}, [], wanting=1, localai_honours=True), (1, []))

    def test_day_paa_never_more_than_one(self):
        jobs = [Job("a", [7], 100, False)]
        self.assertEqual(decide(DAY, mem({7}), {7}, jobs, wanting=2, localai_honours=True), (0, []))

    def test_day_paa_at_its_one_is_never_paused(self):
        # 07:00: LocalAI 0,1 + PAA 7 = 3 > 2 -> LocalAI must give back, not PAA
        jobs = [Job("a", [7], 100, False)]
        self.assertEqual(decide(DAY, mem({0, 1, 7}), {0, 1, 7}, jobs, localai_honours=True), (0, []))

    def test_morning_paa_with_two_gives_back_the_second(self):
        jobs = [Job("old", [6], 100, False), Job("new", [7], 200, False)]
        self.assertEqual(decide(DAY, mem({0, 6, 7}), {0, 6, 7}, jobs, localai_honours=True), (0, ["new"]))

    def test_night_second_card_only_if_room(self):
        # LocalAI 3 + PAA 1 = 4 = cap, PAA wants more -> no 2nd card
        jobs = [Job("a", [7], 100, False)]
        self.assertEqual(decide(NIGHT, mem({0, 1, 2, 7}), {0, 1, 2, 7}, jobs, wanting=1, localai_honours=True)[0], 0)
        # LocalAI 2 + PAA 1 = 3 < 4 and an idle card beyond the kept one -> 2nd card
        self.assertEqual(decide(NIGHT, mem({0, 1, 7}), {0, 1, 7}, jobs, wanting=1, localai_honours=True)[0], 1)

    def test_night_guaranteed_card_never_the_last_idle_one(self):
        # LocalAI 4 (= cap), only 1 idle card (kept for others): PAA waits for LocalAI to pause a run
        self.assertEqual(decide(NIGHT, mem({0, 1, 2, 3}, {4, 5, 6}), {0, 1, 2, 3}, [], wanting=1,
                                localai_honours=True)[0], 0)

    def test_held_second_card_is_released(self):
        # night, PAA finished on two cards and holds both: the 2nd held card is not "need" -> release it
        jobs = [Job("a", [6], 100, True), Job("b", [7], 200, True)]
        self.assertEqual(decide(NIGHT, mem({0, 6, 7}), {0, 6, 7}, jobs, localai_honours=True), (0, ["b"]))

    def test_one_held_card_is_kept(self):
        jobs = [Job("a", [7], 100, True)]
        self.assertEqual(decide(DAY, mem({0, 7}), {0, 7}, jobs, localai_honours=True), (0, []))


if __name__ == "__main__":
    unittest.main(verbosity=1)
