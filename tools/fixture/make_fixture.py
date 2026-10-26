#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
IBM AML(HI-Small) 축소본 만들기 — 통합 테스트·CI용 고정 데이터.

원칙
  - 날짜로 자르지 않고 "패턴 단위"로 자른다. 패턴에 등장하는 계좌의 거래를 전체 기간에서 전부 가져온다.
  - 거기에 정상 거래를 무작위로 섞는다(seed 고정 → 항상 같은 파일).
  - 원본과 같은 컬럼·형식으로 저장한다. 적재 배치(LoadIbmDataset)가 경로만 바꿔 그대로 읽는다.
  - 결과 옆에 expected.json(정답지)을 같이 쓴다. alerts 칸은 룰 정의 확정 후 준영 님이 채운다.

사용
  python tools/make_fixture.py \
      --trans  data/HI-Small_Trans.csv \
      --patterns data/HI-Small_Patterns.txt \
      --out   src/test/resources/fixtures/ibm_small \
      --per-type 2 --background 7000 --seed 20261027

입력 형식 (Kaggle "IBM Transactions for Anti Money Laundering")
  Trans.csv 헤더: Timestamp,From Bank,Account,To Bank,Account.1,Amount Received,Receiving Currency,
                   Amount Paid,Payment Currency,Payment Format,Is Laundering
  Patterns.txt:    BEGIN LAUNDERING ATTEMPT - FAN-OUT
                   <Trans.csv와 같은 형식의 행들>
                   END LAUNDERING ATTEMPT
"""
import argparse, csv, hashlib, io, json, os, random, sys
from collections import Counter, defaultdict
from datetime import datetime

import pandas as pd

COLS = ["Timestamp", "From Bank", "Account", "To Bank", "Account.1", "Amount Received",
        "Receiving Currency", "Amount Paid", "Payment Currency", "Payment Format", "Is Laundering"]
TYPES = ["FAN-OUT", "FAN-IN", "GATHER-SCATTER", "SCATTER-GATHER", "CYCLE", "RANDOM", "BIPARTITE", "STACK"]


def sha256(path, limit=None):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_patterns(path):
    """Patterns.txt → [{'type': 'FAN-IN', 'rows': [list[str]...]}]"""
    patterns, cur = [], None
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("BEGIN LAUNDERING ATTEMPT"):
                ptype = line.split("-", 1)[1].strip().upper() if "-" in line else "UNKNOWN"
                cur = {"type": ptype, "rows": []}
            elif line.startswith("END LAUNDERING ATTEMPT"):
                if cur: patterns.append(cur)
                cur = None
            elif cur is not None and line.strip():
                cur["rows"].append(next(csv.reader([line])))
    return patterns


def pick_patterns(patterns, per_type, rng, max_rows=80):
    """유형마다 per_type개. 너무 큰 패턴(> max_rows 거래)은 피한다 — 축소본이 커진다."""
    by_type = defaultdict(list)
    for p in patterns:
        by_type[p["type"]].append(p)
    chosen = []
    for t in TYPES:
        cands = [p for p in by_type.get(t, []) if len(p["rows"]) <= max_rows] or by_type.get(t, [])
        rng.shuffle(cands)
        chosen += cands[:per_type]
    return chosen


def key(bank, acct):
    return f"{bank.strip()}|{acct.strip()}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trans", required=True)
    ap.add_argument("--patterns", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--per-type", type=int, default=2)
    ap.add_argument("--background", type=int, default=7000, help="섞을 정상 거래 수(대략)")
    ap.add_argument("--seed", type=int, default=20261027)
    ap.add_argument("--chunk", type=int, default=500_000)
    a = ap.parse_args()

    rng = random.Random(a.seed)
    os.makedirs(a.out, exist_ok=True)

    # 1) 패턴 고르기 → 계좌 집합
    patterns = read_patterns(a.patterns)
    chosen = pick_patterns(patterns, a.per_type, rng)
    accounts = set()
    pattern_rows = set()  # (timestamp, from, to, amount_paid) — 패턴 거래 식별용
    for p in chosen:
        for r in p["rows"]:
            accounts.add(key(r[1], r[2])); accounts.add(key(r[3], r[4]))
            pattern_rows.add((r[0], key(r[1], r[2]), key(r[3], r[4]), r[7]))
    print(f"patterns: {len(patterns)} total, {len(chosen)} chosen, {len(accounts)} accounts", file=sys.stderr)

    # 2) 원본 전체 행 수 (배경 샘플 확률 계산용)
    with open(a.trans, "rb") as f:
        total = sum(1 for _ in f) - 1
    p_bg = min(1.0, a.background / max(total, 1))
    print(f"source rows: {total:,}  background p = {p_bg:.6f}", file=sys.stderr)

    # 3) 한 번 훑으며 ① 패턴 계좌가 걸린 행 전부 ② 정상 거래 무작위 샘플
    kept = []
    bg_rng = random.Random(a.seed + 1)
    for chunk in pd.read_csv(a.trans, dtype=str, chunksize=a.chunk, keep_default_na=False):
        chunk.columns = COLS  # 'Account' 중복 컬럼을 pandas가 'Account.1'로 바꾼 것을 고정
        fk = chunk["From Bank"].str.strip() + "|" + chunk["Account"].str.strip()
        tk = chunk["To Bank"].str.strip() + "|" + chunk["Account.1"].str.strip()
        hit = fk.isin(accounts) | tk.isin(accounts)
        lab = chunk["Is Laundering"].str.strip() == "1"
        # 배경: 패턴 계좌와 무관하고, 라벨 0인 행만 확률 p로
        draw = pd.Series([bg_rng.random() < p_bg for _ in range(len(chunk))], index=chunk.index)
        bg = (~hit) & (~lab) & draw
        kept.append(chunk[hit | bg])
    df = pd.concat(kept, ignore_index=True)

    # 패턴 계좌에 걸렸지만 "선택하지 않은 다른 패턴"의 라벨 행은 뺀다 → labeled = 선택한 패턴만
    is_pat = df.apply(lambda r: (r["Timestamp"], key(r["From Bank"], r["Account"]),
                                 key(r["To Bank"], r["Account.1"]), r["Amount Paid"]) in pattern_rows, axis=1)
    df = df[~((df["Is Laundering"].str.strip() == "1") & ~is_pat)]

    df["_ts"] = pd.to_datetime(df["Timestamp"], format="%Y/%m/%d %H:%M", errors="coerce")
    df = df.sort_values(["_ts", "From Bank", "Account"]).drop(columns="_ts").reset_index(drop=True)

    # 4) 저장 — 원본과 같은 헤더(두 번째 Account 컬럼 이름은 원본처럼 'Account')
    out_trans = os.path.join(a.out, "HI-Small_Trans.csv")
    with open(out_trans, "w", newline="", encoding="utf-8") as f:
        f.write("Timestamp,From Bank,Account,To Bank,Account,Amount Received,Receiving Currency,"
                "Amount Paid,Payment Currency,Payment Format,Is Laundering\n")
        df.to_csv(f, header=False, index=False)
    out_pat = os.path.join(a.out, "HI-Small_Patterns.txt")
    with open(out_pat, "w", encoding="utf-8") as f:
        for p in chosen:
            f.write(f"BEGIN LAUNDERING ATTEMPT - {p['type']}\n")
            for r in p["rows"]:
                f.write(",".join(r) + "\n")
            f.write("END LAUNDERING ATTEMPT\n\n")

    # 5) 정답지
    ts = pd.to_datetime(df["Timestamp"], format="%Y/%m/%d %H:%M", errors="coerce")
    days = sorted(ts.dt.strftime("%Y-%m-%d").unique().tolist())
    per_day = ts.dt.strftime("%Y-%m-%d").value_counts().sort_index().to_dict()
    acc_all = set((df["From Bank"].str.strip() + "|" + df["Account"].str.strip()).tolist()
                  + (df["To Bank"].str.strip() + "|" + df["Account.1"].str.strip()).tolist())
    expected = {
        "_meta": {
            "made_by": "tools/make_fixture.py",
            "made_at": datetime.now().strftime("%Y-%m-%d %H:%M"),
            "seed": a.seed, "per_type": a.per_type, "background_target": a.background,
            "source_trans_sha256": sha256(a.trans), "source_patterns_sha256": sha256(a.patterns),
            "note": "rows/labeled/accounts/patterns/per_day 는 이 스크립트가 계산. alerts 는 룰 정의 확정(10/27) 후 준영 님이 pandas 로 채움 — 초안은 tools/draft_alerts.py",
        },
        "rows": int(len(df)),
        "labeled": int((df["Is Laundering"].str.strip() == "1").sum()),
        "accounts": len(acc_all),
        "pattern_accounts": len(accounts),
        "patterns": dict(Counter(p["type"] for p in chosen)),
        "date_range": [days[0], days[-1]] if days else [],
        "per_day": {k: int(v) for k, v in per_day.items()},
        "amount_paid_sum_by_currency": {k: round(float(v), 2) for k, v in
                                        df.assign(_a=pd.to_numeric(df["Amount Paid"], errors="coerce"))
                                        .groupby("Payment Currency")["_a"].sum().to_dict().items()},
        "alerts": {"_status": "draft — 룰 정의 확정 후 채움", "by_date": {d: {f"R0{i}": None for i in range(1, 9)} for d in days}},
    }
    with open(os.path.join(a.out, "expected.json"), "w", encoding="utf-8") as f:
        json.dump(expected, f, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in expected.items() if k not in ("per_day", "alerts")}, ensure_ascii=False, indent=2))
    print(f"\nwrote {out_trans} ({os.path.getsize(out_trans)/1e6:.2f} MB), {out_pat}, expected.json", file=sys.stderr)


if __name__ == "__main__":
    main()
