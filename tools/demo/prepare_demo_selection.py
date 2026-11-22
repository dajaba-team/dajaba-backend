"""Validate and rank the frozen 9/7 ML and DL results without rerunning inference."""
import argparse
from collections import Counter
from datetime import date, timedelta
import hashlib
import math
from pathlib import Path
import sys

DAY = date(2022, 9, 7)
RULES = tuple(f"R{i:02d}" for i in range(1, 7))
DL_FILE_SHA = "b4269eeb645191e2c8cbe1e8a236449a04d911a8c95416fd4283bf9fa086fb00"
RANK_FILE_SHA = "2dcf7bac6b464cbd295f2e5df63b6daa77988412c7d691d3a75c9dcdcf380328"


def select_cases(rankings):
    eligible = sorted((r for r in rankings if r["ml_rank"] is not None), key=lambda r: r["fused_rank"])
    first_by_rule = {}
    for row in eligible:
        first_by_rule.setdefault(row["rule_id"][:3], row)
    selected = [(first_by_rule[rule], "rule_fused_first") for rule in RULES if rule in first_by_rule]
    reserved = {r["alert_id"] for r, _ in selected}
    others = [r for r in eligible if r["alert_id"] not in reserved]
    selected.extend((r, "fused_fill") for r in others[:30 - len(selected)])
    return eligible, selected, [rule for rule in RULES if rule not in first_by_rule]


def run(args):
    sys.path[:0] = [str(args.delivery / "src"), str(args.handoff / "continuation_20261121_232208")]
    from dajaba_submission.demo import observed_evidence, rank_alerts
    from dajaba_submission.dl.graph import EPOCH, key, stamp
    from dajaba_submission.io import read_csv, require, sha256, unique_index, write_csv, write_json
    from run_full_dl import score_baseline

    alerts, evidence = observed_evidence(args.scan)
    require((len(alerts), len(evidence)) == (1545, 13170), "Frozen alert/evidence population differs")
    require(len({a["alert_id"] for a in alerts.values()}) == len(alerts), "Duplicate numeric alert ID")
    require(all(a["alert_no"].split("-")[1] == DAY.strftime("%Y%m%d") and a["rule_id"][:3] in RULES
                for a in alerts.values()), "Unexpected demonstration date or rule")
    ml = score_baseline(args.handoff / "results/final_instruction_ml_0907", args.scan, alerts)
    full = args.handoff / "continuation_20261121_232208"
    require(sha256(full / "dl_transaction_scores.csv") == DL_FILE_SHA, "Frozen DL scores differ")
    require(sha256(full / "rankings.csv") == RANK_FILE_SHA, "Frozen rank evidence differs")
    require(sha256(args.mapping) ==
            "c31b7f34dbb2251d8556c4ab4e0f0609c43fba075138b55724e6def058a8ecdf", "Wrong source map")
    mapping = read_csv(args.mapping)
    source = unique_index(mapping, lambda r: int(r["transaction_id"]), "service transaction")
    unique_index(mapping, key, "source transaction")
    require(source.keys() == evidence.keys(), "Frozen source map coverage differs")
    frozen_dl = unique_index(read_csv(full / "dl_transaction_scores.csv"),
                             lambda r: int(r["transaction_id"]), "DL score")
    require(frozen_dl.keys() == evidence.keys(), "Unexpected frozen DL population")
    scores = {}
    for uid, row in frozen_dl.items():
        minute, score = int(row["minute_since_2022_09_01"]), float(row["score"])
        require(key(source[uid]) == key(row) and
                minute == (stamp(evidence[uid]["ts"]) - EPOCH) // timedelta(minutes=1)
                and math.isfinite(score) and 0 <= score <= 1, "DL identity, time or score mismatch")
        scores[uid] = (minute, score)
    rankings = rank_alerts(alerts, ml, scores, {})
    saved = unique_index(read_csv(full / "rankings.csv"), lambda r: r["alert_id"], "frozen ranks")
    require(saved.keys() == alerts.keys() and all(
        all((int(v) if (v := saved[r["alert_id"]][k]) else None) == r[k]
            for k in ("ml_rank", "fused_rank", "dl_linked"))
        for r in rankings), "Frozen rank parity differs")
    # Also verify that excluding one linked transaction leaves affected alerts in their ML slots.
    removed = int(alerts[rankings[0]["alert_id"]]["transaction_ids"].split()[0])
    fallback = rank_alerts(alerts, ml, {k: v for k, v in scores.items() if k != removed}, {})
    affected = [r for r in fallback if r["link_status"] != "complete"]
    require(affected and all(r["ml_rank"] == r["fused_rank"] for r in affected), "ML fallback moved")

    eligible, selected, missing = select_cases(rankings)
    rows = [dict(selection_order=i, batch_date=DAY.isoformat(), alert_no=r["alert_id"],
                 alert_id=alerts[r["alert_id"]]["alert_id"], rule_id=r["rule_id"], score=r["ml_score"],
                 selection_basis=basis, dl_status=r["link_status"], ml_rank=r["ml_rank"],
                 fused_rank=r["fused_rank"]) for i, (r, basis) in enumerate(selected, 1)]
    args.output.mkdir(parents=True, exist_ok=False)
    require(rows, "No score-success alerts available")
    write_csv(args.output / "selected.csv", rows, list(rows[0]))
    # Narrow backend input: immutable alert identifiers and stored rc3 scores.
    manifest = args.output / "case-selection.tsv"
    manifest.write_text(DAY.isoformat() + "\n" + "".join(
        f'{r["alert_no"]}\t{r["alert_id"]}\t{r["score"]}\n' for r in rows), encoding="utf-8", newline="\n")
    write_json(args.output / "selection-summary.json", dict(
        date=DAY.isoformat(), selected=len(rows), eligible=len(eligible),
        by_rule=dict(Counter(r["rule_id"][:3] for r in rows)), missing_rules=missing,
        dl_applied_population=sum(r["link_status"] == "complete" for r in eligible),
        ml_fallback_population=sum(r["link_status"] != "complete" for r in eligible),
        selected_dl_applied=sum(r["dl_status"] == "complete" for r in rows),
        selected_ml_fallback=sum(r["dl_status"] != "complete" for r in rows),
        missing_score_test_affected=len(affected), manifest_sha256=hashlib.sha256(manifest.read_bytes()).hexdigest()))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("handoff", "delivery", "scan", "mapping", "output"):
        parser.add_argument("--" + flag, type=Path, required=True)
    run(parser.parse_args())
