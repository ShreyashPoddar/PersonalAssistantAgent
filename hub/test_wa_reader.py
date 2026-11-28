"""WhatsApp Desktop chat-list parsing and new-message detection (made-up rows).

    cd hub && python test_wa_reader.py
"""
import unittest

from wa_reader import Inbox, Reader, parse_row


class ParseTest(unittest.TestCase):
    def test_person(self):
        p = parse_row("Riya Sharma 2:11 am kal tak notes bhej dena")
        self.assertEqual(("Riya Sharma", None, "kal tak notes bhej dena", False, False),
                         (p["chat"], p["sender"], p["text"], p["outgoing"], p["group"]))

    def test_group_sender_and_muted(self):
        p = parse_row("CSE Batch 28 1:03 am Muted chat ~Arjun :  @Sam register kar lena")
        self.assertEqual(("CSE Batch 28", "Arjun", "@Sam register kar lena", True),
                         (p["chat"], p["sender"], p["text"], p["group"]))

    def test_own_message_and_yesterday(self):
        p = parse_row("Mummy Yesterday You : haan kar dunga")
        self.assertEqual(("Mummy", True, "haan kar dunga"), (p["chat"], p["outgoing"], p["text"]))

    def test_unread_count_dropped(self):
        p = parse_row("Dev 9:41 pm 2 unread messages ppt bhej")
        self.assertEqual("ppt bhej", p["text"])

    def test_no_time_is_skipped(self):
        self.assertIsNone(parse_row("Archived"))


class ReaderTest(unittest.TestCase):
    def test_seed_then_changes_only(self):
        snaps = [["Riya 1:00 pm hi", "Dev 1:00 pm ok"], ["Riya 1:05 pm notes bhej", "Dev 1:00 pm ok"],
                 ["Riya 1:06 pm You : bhej diya", "Dev 1:00 pm ok"], None]
        box = Inbox()
        r = Reader(box, snapshot=lambda: snaps.pop(0))
        self.assertEqual(0, r.step())          # first snapshot only seeds
        self.assertEqual(1, r.step())          # Riya's new preview
        self.assertEqual(0, r.step())          # the owner's own message is not an incoming message
        self.assertEqual(0, r.step())          # window closed: nothing, no crash
        got = box.after(0)
        self.assertEqual(["notes bhej"], [i["text"] for i in got["items"]])
        self.assertEqual([], box.after(got["last"])["items"])

    def run_snaps(self, snaps):
        box = Inbox()
        r = Reader(box, snapshot=lambda: snaps.pop(0))
        counts = [r.step() for _ in range(len(snaps))]
        return counts, [i["text"] for i in box.after(0)["items"]]

    def test_typing_then_old_preview_is_not_new(self):
        counts, texts = self.run_snaps([["Riya 1:00 pm hi"], ["Riya 1:00 pm typing…"], ["Riya 1:00 pm hi"],
                                        ["Riya 1:01 pm Arjun is typing..."], ["Riya 1:02 pm notes bhej"]])
        self.assertEqual([0, 0, 0, 0, 1], counts)
        self.assertEqual(["notes bhej"], texts)

    def test_deleted_placeholder_is_not_an_event(self):
        counts, _ = self.run_snaps([["Dev 1:00 pm ok"], ["Dev 1:01 pm This message was deleted"], ["Dev 1:02 pm ppt bhej"]])
        self.assertEqual([0, 0, 1], counts)

    def test_chat_scrolled_into_view_is_old(self):
        top = ["A 1:00 pm a", "B 1:00 pm b", "C 1:00 pm c"]
        counts, texts = self.run_snaps([top, top + ["Old 9:00 am purana"], ["New 1:05 pm naya"] + top])
        self.assertEqual([0, 0, 1], counts)    # first seen far down: old; first seen at the top: new
        self.assertEqual(["naya"], texts)


if __name__ == "__main__":
    unittest.main()
