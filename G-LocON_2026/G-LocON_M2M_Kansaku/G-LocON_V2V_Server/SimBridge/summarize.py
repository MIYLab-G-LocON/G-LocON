"""実行結果の集計（サービス指標）.

    python summarize.py                 # out/ 以下の全実行を比較
    python summarize.py none_s1 ideal_s1_t15_d30 ideal_s1_t15_d60 ideal_s1_t15_d100

指標:
    near_miss      TTC < 3秒 となった危険な接近の件数（SSM）
    min_ttc        最小TTC [秒]
    drac_over3     DRAC（衝突回避に必要な減速度）が 3 m/s^2 を超えた件数
    hard_brake     急制動（加速度 < -4 m/s^2）の発生回数（連続区間は1回）
    hard_brake_veh 急制動した車両数（急停止連鎖の規模）
    rear_end       追突件数（急停止に起因するもの）
    junction_col   交差点内の接触件数（SUMOの交差点モデル由来のものを含むため参考値）
    *_hz           急停止の発生から30秒以内に限った値（V2Vの効果が直接現れる範囲）
    mean_duration  平均所要時間 [秒]，mean_wait 平均停止時間 [秒]，fuel_l 総燃料 [L]

  急停止した車に後ろから近づいた車（followers.csv。通信とは無関係に SUMO の正解から拾う）だけを見た指標:
    followers      急停止した車の後ろ 150m 以内に近づいた車の数（のべ。急停止が終わる 3秒前より後に来た車は除く）
    foll_warned    そのうち減速指示が届いた車の数，coverage はその割合 [%]
    warn_gap       減速指示が届いた時点の，急停止した車までの道のり（中央値）[m]
    warn_lead      同じ時点の「道のり ÷ 速度」（中央値）[秒]。大きいほど余裕を持って届いている
    foll_decel     近づいた車の最大減速度の平均 [m/s^2]，foll_hard はそれが 4.5 を超えた車の数
    foll_v_near    急停止した車の 30m 手前まで来たときの速度の平均 [m/s]（その前に急停止が終わった車は除く）
"""
import csv
import os
import sys
import xml.etree.ElementTree as ET

import common
import hazard_eval

HAZARD_WINDOW = 30.0
HARD_BRAKE = -4.0   # 快適な減速度(3.0)を明確に超える減速


def fnum(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def in_window(t, stops):
    return any(s <= t <= s + HAZARD_WINDOW for s in stops)


def ssm_stats(path, stops):
    near, near_hz, drac3, min_ttc = 0, 0, 0, None
    if not os.path.exists(path):
        return near, near_hz, min_ttc, drac3
    for _, el in ET.iterparse(path):
        if el.tag != "conflict":
            continue
        ttc = el.find("minTTC")
        v = fnum(ttc.get("value")) if ttc is not None else None
        if v is not None and v < 3.0:
            near += 1
            near_hz += in_window(fnum(ttc.get("time")) or -1, stops)
            min_ttc = v if min_ttc is None else min(min_ttc, v)
        dr = el.find("maxDRAC")
        dv = fnum(dr.get("value")) if dr is not None else None
        if dv is not None and dv > 3.0:
            drac3 += 1
        el.clear()
    return near, near_hz, min_ttc, drac3


def fcd_stats(path, stops):
    if not os.path.exists(path):
        return None, None, None
    braking, events, vehs, ev_hz = set(), 0, set(), 0
    now = 0.0
    for ev, el in ET.iterparse(path, events=("start", "end")):
        if ev == "start":
            if el.tag == "timestep":
                now = float(el.get("time"))
            continue
        if el.tag == "vehicle":
            vid = el.get("id")
            acc = fnum(el.get("acceleration"))
            if acc is not None and acc < HARD_BRAKE:
                if vid not in braking:
                    events += 1
                    ev_hz += in_window(now, stops)
                    vehs.add(vid)
                braking.add(vid)
            else:
                braking.discard(vid)
        elif el.tag == "timestep":
            el.clear()
    return events, len(vehs), ev_hz


def trip_stats(path):
    durs, waits, fuel = [], [], 0.0
    for _, el in ET.iterparse(path):
        if el.tag == "tripinfo":
            durs.append(float(el.get("duration")))
            waits.append(float(el.get("waitingTime")))
        elif el.tag == "emissions":
            fuel += float(el.get("fuel_abs", 0))
        el.clear()
    n = len(durs) or 1
    # fuel_abs は mg。ガソリン密度 0.745 kg/L 換算
    return len(durs), sum(durs) / n, sum(waits) / n, fuel / 1e6 / 0.745


def collisions(path):
    """(追突, 交差点内の接触) の件数。急停止で起きるのは追突."""
    if not os.path.exists(path):
        return 0, 0
    rear, junc = 0, 0
    for _, el in ET.iterparse(path):
        if el.tag == "collision":
            if el.get("type") == "junction":
                junc += 1
            else:
                rear += 1
    return rear, junc


def median(xs):
    xs = sorted(xs)
    return round(xs[len(xs) // 2], 1) if xs else None


def follower_stats(path):
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if float(r["time_left"]) >= hazard_eval.MIN_TIME_LEFT]
    if not rows:
        return {"followers": 0}
    w = [r for r in rows if r["warned"] == "1"]
    dec = [float(r["max_decel"]) for r in rows]
    near = [float(r["speed_near"]) for r in rows if r["speed_near"]]
    return {"followers": len(rows), "foll_warned": len(w), "coverage": round(100 * len(w) / len(rows), 1),
            "warn_gap": median([float(r["gap_warn"]) for r in w]),
            "warn_lead": median([float(r["gap_warn"]) / max(float(r["speed_warn"]), 1.0) for r in w]),
            "foll_decel": round(sum(dec) / len(dec), 2), "foll_hard": sum(d > 4.5 for d in dec),
            "foll_v_near": round(sum(near) / len(near), 1) if near else None}


FOLL_KEYS = ["followers", "foll_warned", "coverage", "warn_gap", "warn_lead", "foll_decel", "foll_hard", "foll_v_near"]


def summarize(name):
    d = os.path.join(common.OUT_DIR, name)
    warned, stop_times = 0, []
    hp = os.path.join(d, "hazard_log.csv")
    if os.path.exists(hp):
        with open(hp, encoding="utf-8") as f:
            for r in csv.DictReader(f):
                warned += r["event"] == "DECELERATE"
                if r["event"] == "SUDDEN_STOP":
                    stop_times.append(float(r["t"]))
    stops = len(stop_times)
    near, near_hz, min_ttc, drac3 = ssm_stats(os.path.join(d, "ssm.xml"), stop_times)
    hb, hbv, hb_hz = fcd_stats(os.path.join(d, "fcd.xml"), stop_times)
    n, dur, wait, fuel = trip_stats(os.path.join(d, "tripinfo.xml"))
    col = collisions(os.path.join(d, "collisions.xml"))
    fs = follower_stats(os.path.join(d, "followers.csv"))
    return {"run": name, "vehicles": n, "sudden_stops": stops, "warned": warned,
            **{k: fs.get(k) for k in FOLL_KEYS},
            "near_miss": near, "near_miss_hz": near_hz, "min_ttc": None if min_ttc is None else round(min_ttc, 2),
            "drac_over3": drac3, "hard_brake": hb, "hard_brake_hz": hb_hz, "hard_brake_veh": hbv,
            "rear_end": col[0], "junction_col": col[1],
            "mean_duration": round(dur, 1), "mean_wait": round(wait, 1), "fuel_l": round(fuel, 2)}


def main():
    names = sys.argv[1:] or sorted(n for n in os.listdir(common.OUT_DIR)
                                   if os.path.isfile(os.path.join(common.OUT_DIR, n, "tripinfo.xml")))
    rows = [summarize(n) for n in names]
    keys = list(rows[0].keys())
    out = os.path.join(common.OUT_DIR, "summary.csv")
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, keys); w.writeheader(); w.writerows(rows)
    width = max(len(k) for k in keys)
    for k in keys:
        print(f"{k:<{width}}  " + "  ".join(f"{str(r[k]):>12}" for r in rows))
    print(f"→ {out}")


if __name__ == "__main__":
    main()
