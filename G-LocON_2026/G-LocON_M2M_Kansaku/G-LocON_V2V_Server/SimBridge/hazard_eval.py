"""急停止イベントの共通処理（run_scenario.py と sim_bridge.py で同じものを使う）.

  - 後続車の記録（followers.csv）: 急停止した車に実際に後ろから近づいた車を，通信とは無関係に SUMO の正解から拾い，
    その車が減速指示を受けたか・どれだけ手前で受けたか・どのくらい強くブレーキを踏んだかを記録する。
    V2Vなし／理想V2V／本システムで同じ基準になるので，「近づいた車のうち何台に届いたか」を比べられる。
  - 後続車がいるときだけ急停止させる規則（--hazard-rule follower）
  - 減速指示を受けた車の減速のさせ方（slow_down_step）
"""
import csv
import json
import os

import common

FOLLOW_RANGE_M = 150.0     # 後続車とみなす範囲（急停止した車までの道のり）
MIN_TIME_LEFT = 3.0        # 集計に入れる後続車: 範囲に入ってから急停止が終わるまでの時間 [秒]
NEAR_M = 30.0              # 「すぐ手前」の速度を記録する位置
DECEL_RATE = 2.0           # 減速指示を受けた車の減速度 [m/s^2]
FOLLOWER_MIN_M = 20.0      # --hazard-rule follower: 後続車がこの範囲にいるときだけ急停止させる
FOLLOWER_MIN_SPEED = 5.0
SAME_ES_INTERVAL = 40.0    # --hazard-rule follower: 同じ交差点で続けて急停止させない間隔 [秒]
ANY_INTERVAL = 15.0        # --hazard-rule follower: エリア全体で続けて急停止させない間隔 [秒]（15分で最大60件ほど）


def load_hazards(rule):
    name = "hazards.json" if rule == "fixed" else "hazard_candidates.json"
    with open(os.path.join(common.SCENARIO_DIR, name), encoding="utf-8") as f:
        return json.load(f)


def gap_behind(traci, m, h_edge, h_pos, limit=FOLLOW_RANGE_M):
    """車 m から急停止した車までの道のり [m]。後ろから近づいていなければ None."""
    d = traci.vehicle.getDrivingDistance(m, h_edge, h_pos)
    if d is None or d <= 0 or d > limit:
        return None
    return d


def follower_exists(traci, v, alive):
    """v の後ろ FOLLOWER_MIN_M〜FOLLOW_RANGE_M に，走っている後続車がいるか."""
    e, p = traci.vehicle.getRoadID(v), traci.vehicle.getLanePosition(v)
    if e.startswith(":"):
        return False
    for m in alive:
        if m == v:
            continue
        d = gap_behind(traci, m, e, p)
        if d is not None and d >= FOLLOWER_MIN_M and traci.vehicle.getSpeed(m) >= FOLLOWER_MIN_SPEED:
            return True
    return False


def may_stop(last_stop, iid, now):
    """--hazard-rule follower: 前の急停止から十分に時間があいているか（last_stop: 交差点→時刻，"*"=全体）."""
    return (now - last_stop.get(iid, -1e9) >= SAME_ES_INTERVAL
            and now - last_stop.get("*", -1e9) >= ANY_INTERVAL)


def mark_stop(last_stop, iid, now):
    last_stop[iid] = now
    last_stop["*"] = now


def slow_down_step(traci, m, warn_speed, step_len):
    """減速指示を受けた車の速度の上限を1ステップ分下げる（DECEL_RATE で warn_speed まで）.

    SUMO は上限を超えた車を次のステップで上限まで一気に落とすので，上限は「今の速度」から少しずつ下げる
    （時刻だけで下げると，車がついて来られなかった分を一度に落とし，急ブレーキになる）。
    前の車に合わせて既に DECEL_RATE 以上で減速しているときは，それ以上は加えない。
    前の車との車間は車両モデル（IDM）が保つので，上限を下げても追突はしない。
    """
    v = traci.vehicle.getSpeed(m)
    braking = traci.vehicle.getAcceleration(m) <= -DECEL_RATE
    cap = max(warn_speed, v if braking else v - DECEL_RATE * step_len)
    traci.vehicle.setMaxSpeed(m, cap)
    return cap


class FollowerTracker:
    """急停止した車に後ろから近づいた車（正解）を記録する."""

    COLS = ["hazard_vehicle", "intersectionId", "t_stop", "follower", "t_first", "time_left", "gap_first", "speed_first",
            "warned", "t_warn", "gap_warn", "speed_warn", "max_decel", "speed_near"]

    def __init__(self, traci, path):
        self.t = traci
        self.path = path
        self.rec = {}          # (hazard_vehicle, t_stop) -> {follower: 記録}

    def step(self, now, stopping, warned, alive):
        """stopping: [{veh, iid, t0, until}]，warned: 減速中の車の集合.

        time_left は，その車が範囲に入った時点で急停止が終わるまでの残り時間。
        終わる直前に入った車は反応する必要がないので，集計（summarize.py）では MIN_TIME_LEFT 秒未満を除く。
        """
        t = self.t
        for s in stopping:
            hv = s["veh"]
            if hv not in alive:
                continue
            e, p = t.vehicle.getRoadID(hv), t.vehicle.getLanePosition(hv)
            if e.startswith(":"):
                continue
            fl = self.rec.setdefault((hv, s["iid"], s["t0"]), {})
            for m in alive:
                if m == hv:
                    continue
                d = gap_behind(t, m, e, p)
                if d is None:
                    continue
                sp, ac = t.vehicle.getSpeed(m), t.vehicle.getAcceleration(m)
                f = fl.get(m)
                if f is None:
                    f = fl[m] = {"t_first": now, "time_left": s["until"] - now, "gap_first": d, "speed_first": sp, "warned": 0,
                                 "t_warn": "", "gap_warn": "", "speed_warn": "", "max_decel": 0.0, "speed_near": ""}
                f["max_decel"] = max(f["max_decel"], -ac)
                if d <= NEAR_M and f["speed_near"] == "":
                    f["speed_near"] = round(sp, 2)
                if m in warned and not f["warned"]:
                    f.update(warned=1, t_warn=now, gap_warn=round(d, 1), speed_warn=round(sp, 2))

    def close(self):
        with open(self.path, "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(self.COLS)
            for (hv, iid, t0), fl in self.rec.items():
                for m, f in fl.items():
                    w.writerow([hv, iid, f"{t0:.1f}", m, f"{f['t_first']:.1f}", f"{f['time_left']:.1f}", f"{f['gap_first']:.1f}",
                                f"{f['speed_first']:.2f}", f["warned"], f["t_warn"], f["gap_warn"], f["speed_warn"],
                                f"{f['max_decel']:.2f}", f["speed_near"]])
