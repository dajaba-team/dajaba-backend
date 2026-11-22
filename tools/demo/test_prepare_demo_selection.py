import unittest

from prepare_demo_selection import select_cases


def alert(uid, rule, ml, fused):
    return dict(alert_id=uid, rule_id=rule, ml_rank=ml, fused_rank=fused)


class DemoSelectionTest(unittest.TestCase):
    def test_fused_first_disagrees_with_ml_first_and_reports_unscored_rule(self):
        rows = [
            alert("ml-first", "R01_FAN_OUT", 1, 3),
            alert("fused-first", "R01_FAN_OUT", 3, 1),
            alert("fallback", "R02_FAN_IN", 2, 2),
            alert("failed", "R03_FX_RELAY", None, None),
        ]
        _, selected, missing = select_cases(rows)
        self.assertEqual([(r["alert_id"], why) for r, why in selected],
                         [("fused-first", "rule_fused_first"), ("fallback", "rule_fused_first"),
                          ("ml-first", "fused_fill")])
        self.assertEqual(missing, ["R03", "R04", "R05", "R06"])

    def test_reserves_each_rule_and_fills_by_global_rank_without_duplicates(self):
        rows = [alert(f"r1-{i}", "R01_FAN_OUT", i, i) for i in range(1, 32)]
        rows.extend(alert(f"r{i}", f"R{i:02d}_OTHER", 30 + i, 30 + i) for i in range(2, 7))
        _, selected, missing = select_cases(rows)
        ids = [row["alert_id"] for row, _ in selected]
        self.assertEqual(ids[:6], ["r1-1", "r2", "r3", "r4", "r5", "r6"])
        self.assertEqual(ids[6:], [f"r1-{i}" for i in range(2, 26)])
        self.assertEqual(len(set(ids)), 30)
        self.assertEqual(missing, [])
