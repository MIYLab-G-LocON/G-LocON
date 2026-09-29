"""実行結果の集計（サービス指標）.

    python summarize.py                 # out/ 以下の全実行を比較
    python summarize.py none_s1 ideal_s1

指標:
    near_miss      TTC < 3秒 となった危険な接近の件数（SSM）
    min_ttc        最小TTC [秒]
    drac_over3     DRAC（衝突回避に必要な減速度）が 3 m/s^2 を超えた件数
    hard_brake     急制動（加速度 < -3 m/s^2）の発生回数（連続区間は1回）
    hard_brake_veh 急制動した車両数（急停止連鎖の規模）
    collisions     衝突件数
    mean_duration  平均所要時間 [秒]，mean_wait 平均停止時間 [秒]，fuel_l 総燃料 [L]
"""
import csv
import os
import sys
import xml.etree.ElementTree as ET

import common

HARD_BRAKE = -3.0


def fnum(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def ssm_stats(path):
    near, drac3, min_ttc = 0, 0, None
    if not os.path.exists(path):
        return near, min_ttc, drac3
    for _, el in ET.iterparse(path):
        if el.tag != "conflict":
            continue
        ttc = el.find("minTTC")
        v = fnum(ttc.get("value")) if ttc is not None else None
        if v is not None and v < 3.0:
            near += 1
            min_ttc = v if min_ttc is None else min(min_ttc, v)
        dr = el.find("maxDRAC")
        dv = fnum(dr.get("value")) if dr is not None else None
        if dv is not None and dv > 3.0:
            drac3 += 1
        el.clear()
    return near, min_ttc, drac3


def fcd_stats(path):
    if not os.path.exists(path):
        return None, None
    braking, events, vehs = set(), 0, set()
    for _, el in ET.iterparse(path):
        if el.tag == "vehicle":
            vid = el.get("id")
            acc = fnum(el.get("acceleration"))
            if acc is not None and acc < HARD_BRAKE:
                if vid not in braking:
                    events += 1
                    vehs.add(vid)
                braking.add(vid)
            else:
                braking.discard(vid)
        el.clear()
    return events, len(vehs)


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
    if not os.path.exists(path):
        return 0
    return sum(1 for _, el in ET.iterparse(path) if el.tag == "collision")


def summarize(name):
    d = os.path.join(common.OUT_DIR, name)
    near, min_ttc, drac3 = ssm_stats(os.path.join(d, "ssm.xml"))
    hb, hbv = fcd_stats(os.path.join(d, "fcd.xml"))
    n, dur, wait, fuel = trip_stats(os.path.join(d, "tripinfo.xml"))
    warned = 0
    hp = os.path.join(d, "hazard_log.csv")
    stops = 0
    if os.path.exists(hp):
        with open(hp, encoding="utf-8") as f:
            for r in csv.DictReader(f):
                warned += r["event"] == "DECELERATE"
                stops += r["event"] == "SUDDEN_STOP"
    return {"run": name, "vehicles": n, "sudden_stops": stops, "warned": warned,
            "near_miss": near, "min_ttc": None if min_ttc is None else round(min_ttc, 2),
            "drac_over3": drac3, "hard_brake": hb, "hard_brake_veh": hbv,
            "collisions": collisions(os.path.join(d, "collisions.xml")),
            "mean_duration": round(dur, 1), "mean_wait": round(wait, 1), "fuel_l": round(fuel, 2)}


def main():
    names = sys.argv[1:] or sorted(n for n in os.listdir(common.OUT_DIR)
                                   if os.path.isdir(os.path.join(common.OUT_DIR, n)))
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
