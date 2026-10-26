#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
expected.json 의 alerts 칸 초안 — 룰 정의를 pandas 로 구현해 축소본에서 날짜별·룰별 경보 수를 뽑는다.

중요
  - 아래 룰 정의는 "규약 v0.1 기준 초안"이다. 10/27 회의에서 확정되면 PARAMS 와 함수 본문을 그 정의로 바꾼다.
  - 백엔드(준혁, SQL)와 여기(pandas)가 같은 숫자를 내야 한다. 다르면 코드가 아니라 "정의 문장"을 먼저 의심한다.
  - 경보 1건 = (룰, 날짜, 대상 계좌). 근거 거래 ID 는 백엔드가 붙인다.

사용
  python tools/draft_alerts.py --fixture src/test/resources/fixtures/ibm_small
  → expected.json 의 alerts.by_date 를 채워 다시 쓴다 (alerts._status 를 'draft(v0.1)' 로).
"""
import argparse, json, os
import pandas as pd

# ---- 규약 v0.1 기준 파라미터 (10/27 확정 후 수정) ----
PARAMS = {
    "R01_FAN_IN":        {"min_senders": 10},            # 수신 계좌가 하루(달력일)에 받은 서로 다른 송신 계좌 수
    "R02_FAN_OUT":       {"min_receivers": 10},          # 송신 계좌가 하루에 보낸 서로 다른 수신 계좌 수
    "R03_STRUCTURING":   {"min_txns": 3, "min_total": 10000.0},  # 같은 송신→수신 쌍, 하루 3건 이상 & 합계 ≥ 1만(지불 통화 기준)
    "R04_CYCLE":         {"window_hours": 48},           # A→B 뒤 48시간 안에 B→A
    "R05_PASS_THROUGH":  {"ratio": 0.8, "window_hours": 24},  # 받은 금액의 80% 이상을 24시간 안에 내보냄
    "R06_HIGH_VALUE":    {"min_amount": 1_000_000.0},    # 단건 금액(지불 통화 기준) ≥ 100만
    "R07_CASH_INTENSIVE": {"min_cash_total": 50_000.0},  # 하루 현금(Payment Format = Cash) 송금 합 ≥ 5만
    "R08_DORMANT_BURST": {"quiet_days": 5, "min_txns": 5},  # 5일 이상 거래 없던 계좌가 하루에 5건 이상
}


def load(fixture_dir):
    df = pd.read_csv(os.path.join(fixture_dir, "HI-Small_Trans.csv"), dtype=str, keep_default_na=False)
    df.columns = ["ts", "from_bank", "from_acct", "to_bank", "to_acct", "amt_recv", "cur_recv",
                  "amt_paid", "cur_paid", "fmt", "label"]
    df["ts"] = pd.to_datetime(df["ts"], format="%Y/%m/%d %H:%M")
    df["day"] = df["ts"].dt.strftime("%Y-%m-%d")
    df["src"] = df["from_bank"].str.strip() + "|" + df["from_acct"].str.strip()
    df["dst"] = df["to_bank"].str.strip() + "|" + df["to_acct"].str.strip()
    df["amt_paid"] = pd.to_numeric(df["amt_paid"], errors="coerce").fillna(0.0)
    df["amt_recv"] = pd.to_numeric(df["amt_recv"], errors="coerce").fillna(0.0)
    return df


# 각 룰은 DataFrame → DataFrame[day, account] (경보 1건 = 1행) 을 돌려준다.
def r01_fan_in(df, p):
    g = df.groupby(["day", "dst"])["src"].nunique().reset_index(name="n")
    return g[g["n"] >= p["min_senders"]][["day", "dst"]].rename(columns={"dst": "account"})


def r02_fan_out(df, p):
    g = df.groupby(["day", "src"])["dst"].nunique().reset_index(name="n")
    return g[g["n"] >= p["min_receivers"]][["day", "src"]].rename(columns={"src": "account"})


def r03_structuring(df, p):
    g = df.groupby(["day", "src", "dst"]).agg(n=("amt_paid", "size"), total=("amt_paid", "sum")).reset_index()
    g = g[(g["n"] >= p["min_txns"]) & (g["total"] >= p["min_total"])]
    return g[["day", "src"]].drop_duplicates().rename(columns={"src": "account"})


def r04_cycle(df, p):
    w = pd.Timedelta(hours=p["window_hours"])
    a = df[["ts", "src", "dst", "day"]].rename(columns={"ts": "t1"})
    b = df[["ts", "src", "dst"]].rename(columns={"ts": "t2", "src": "dst", "dst": "src"})  # 반대 방향
    m = a.merge(b, on=["src", "dst"])
    m = m[(m["t2"] > m["t1"]) & (m["t2"] - m["t1"] <= w)]
    return m[["day", "src"]].drop_duplicates().rename(columns={"src": "account"})


def r05_pass_through(df, p):
    w = pd.Timedelta(hours=p["window_hours"])
    inflow = df[["ts", "dst", "amt_recv", "day"]].rename(columns={"dst": "acct", "ts": "t_in"})
    outflow = df[["ts", "src", "amt_paid"]].rename(columns={"src": "acct", "ts": "t_out"})
    rows = []
    for acct, ins in inflow.groupby("acct"):
        outs = outflow[outflow["acct"] == acct]
        if outs.empty:
            continue
        for _, r in ins.iterrows():
            sent = outs[(outs["t_out"] >= r["t_in"]) & (outs["t_out"] <= r["t_in"] + w)]["amt_paid"].sum()
            if r["amt_recv"] > 0 and sent >= p["ratio"] * r["amt_recv"]:
                rows.append((r["day"], acct))
    return pd.DataFrame(rows, columns=["day", "account"]).drop_duplicates()


def r06_high_value(df, p):
    g = df[df["amt_paid"] >= p["min_amount"]]
    return g[["day", "src"]].drop_duplicates().rename(columns={"src": "account"})


def r07_cash_intensive(df, p):
    g = df[df["fmt"].str.strip().str.lower() == "cash"].groupby(["day", "src"])["amt_paid"].sum().reset_index()
    return g[g["amt_paid"] >= p["min_cash_total"]][["day", "src"]].rename(columns={"src": "account"})


def r08_dormant_burst(df, p):
    act = pd.concat([df[["day", "src"]].rename(columns={"src": "acct"}),
                     df[["day", "dst"]].rename(columns={"dst": "acct"})])
    act["d"] = pd.to_datetime(act["day"])
    cnt = act.groupby(["acct", "d"]).size().reset_index(name="n").sort_values(["acct", "d"])
    cnt["prev"] = cnt.groupby("acct")["d"].shift()
    gap = (cnt["d"] - cnt["prev"]).dt.days
    hit = cnt[(gap >= p["quiet_days"]) & (cnt["n"] >= p["min_txns"])]
    return pd.DataFrame({"day": hit["d"].dt.strftime("%Y-%m-%d"), "account": hit["acct"]})


RULES = {
    "R01": ("R01_FAN_IN", r01_fan_in), "R02": ("R02_FAN_OUT", r02_fan_out),
    "R03": ("R03_STRUCTURING", r03_structuring), "R04": ("R04_CYCLE", r04_cycle),
    "R05": ("R05_PASS_THROUGH", r05_pass_through), "R06": ("R06_HIGH_VALUE", r06_high_value),
    "R07": ("R07_CASH_INTENSIVE", r07_cash_intensive), "R08": ("R08_DORMANT_BURST", r08_dormant_burst),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixture", required=True)
    a = ap.parse_args()
    df = load(a.fixture)
    path = os.path.join(a.fixture, "expected.json")
    exp = json.load(open(path, encoding="utf-8"))
    by_date = {d: {} for d in sorted(df["day"].unique())}
    totals = {}
    for code, (name, fn) in RULES.items():
        hits = fn(df, PARAMS[name])
        totals[code] = int(len(hits))
        counts = hits.groupby("day").size().to_dict() if len(hits) else {}
        for d in by_date:
            by_date[d][code] = int(counts.get(d, 0))
    exp["alerts"] = {
        "_status": "draft(v0.1) — tools/draft_alerts.py 가 계산. 10/27 룰 정의 확정 후 준영 님이 다시 돌려 확정",
        "params": PARAMS,
        "total_by_rule": totals,
        "by_date": by_date,
    }
    json.dump(exp, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps({"total_by_rule": totals, "days": len(by_date)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
