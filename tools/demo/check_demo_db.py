"""Compare a read-only demo database export against the verified input manifest."""
import argparse
from collections import Counter
import csv
from pathlib import Path


def read(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def index(rows):
    result = {r["alert_id"]: r for r in rows}
    if len(result) != len(rows):
        raise ValueError("Duplicate alert ID")
    return result


def check(mode, expected, observed):
    left, right = index(read(expected)), index(read(observed))
    missing = len(left.keys() - right.keys())
    extra = len(right.keys() - left.keys())
    if missing or extra:
        raise ValueError(f"Database alert ID mismatch: missing={missing}, extra={extra}")
    for uid, a in left.items():
        b = right[uid]
        if (a["alert_no"], a["rule_id"]) != (b["alert_no"], b["rule_id"]):
            raise ValueError("Alert identity or rule mismatch")
        if mode == "preflight":
            expected_ids, db_ids = (tuple(map(int, r["transaction_ids"].split())) for r in (a, b))
            if sorted(expected_ids) != sorted(db_ids) or not db_ids:
                raise ValueError("Evidence transaction IDs differ")
            if b["score_status"] != "PENDING" or b["case_opened"] not in ("f", "false"):
                raise ValueError("Expected clean PENDING alerts with no existing cases")
    print(dict(mode=mode, count=len(left), missing=missing, extra=extra,
               by_rule=dict(Counter(r["rule_id"][:3] for r in left.values()))))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("preflight", "result"))
    parser.add_argument("expected", type=Path)
    parser.add_argument("observed", type=Path)
    check(**vars(parser.parse_args()))
