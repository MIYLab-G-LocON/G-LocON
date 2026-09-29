"""段階1-c: シナリオの実行（V2Vなし / 理想V2V の比較用）.

    python run_scenario.py --mode none  --seed 1
    python run_scenario.py --mode ideal --seed 1 [--latency 0.3]
    python run_scenario.py --mode none --gui        # 画面で確認

mode:
    none  : V2Vなし（下限）。急停止の情報は誰にも届かない
    ideal : 理想V2V（上限）。本システムと同じ規則（ETA<τでJOIN，通過してδ離れたらLEAVE）で
            交差点グループを作り，急停止が起きたら同じグループの後続車へ latency 秒後に通知，
            後続車は減速（DECELERATE）し，解消後に復帰（RESUME）する。
            通信の損失・遅延のばらつきは無い。実機・アプリをつないだ実験（段階3以降）の比較対象になる。

出力（out/<mode>_s<seed>/）:
    ssm.xml      TTC・PET・DRAC（SSMデバイス）
    fcd.xml      全車両の位置・速度・加速度の時系列
    tripinfo.xml 車両ごとの所要時間・停止時間・燃料
    group_log.csv  交差点グループの参加・離脱（正解データ）
    hazard_log.csv 急停止イベントと通知の記録
"""
import argparse
import csv
import json
import os
import random

import common


def parse():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["none", "ideal"], default="none")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--latency", type=float, default=0.3, help="ideal時の通知遅延 [秒]")
    ap.add_argument("--warn-speed", type=float, default=5.0, help="通知を受けた車の減速目標 [m/s]")
    ap.add_argument("--gui", action="store_true")
    ap.add_argument("--no-fcd", action="store_true", help="fcd.xmlを出力しない（高速化）")
    ap.add_argument("--tag", default="")
    return ap.parse_args()


class RouteIndex:
    """車両のルートが交差点(junction)を通るかを調べ，交差点までの道のりを返す."""

    def __init__(self, traci, net, junction_ids):
        self.t = traci
        self.jids = junction_ids          # {intersectionId: junctionId}
        self.to_node = {e.getID(): (e.getToNode().getID(), e.getLength()) for e in net.getEdges()}
        self.cache = {}                   # vehicle -> (route, {iid: (edge, length)})

    def targets(self, v):
        c = self.cache.get(v)
        if c is None:                     # ルートは出発時に決まり変更しない前提
            route = self.t.vehicle.getRoute(v)
            hit = {}
            for e in route:
                node, ln = self.to_node.get(e, (None, 0))
                for iid, jid in self.jids.items():
                    if node == jid and iid not in hit:
                        hit[iid] = (e, ln)
            c = (route, hit)
            self.cache[v] = c
        return c[1]

    def distance(self, v, iid):
        """交差点までの道のり [m]。ルート上にない／通過済みなら None."""
        tg = self.targets(v).get(iid)
        if tg is None:
            return None
        d = self.t.vehicle.getDrivingDistance(v, tg[0], tg[1])
        if d is None or d < 0 or d > 1e6:
            return None
        return d


class Groups:
    """本システムと同じ規則で交差点グループを管理する（正解データ兼 理想V2V用）."""

    def __init__(self, traci, junctions, routes, logw):
        self.t = traci
        self.j = junctions            # {intersectionId: (x, y)}
        self.routes = routes
        self.members = {iid: set() for iid in junctions}
        self.prev_dist = {}
        self.logw = logw

    def update(self, now):
        t = self.t
        vids = set(t.vehicle.getIDList())
        for iid, (jx, jy) in self.j.items():
            mem = self.members[iid]
            for v in list(mem):
                if v not in vids:
                    mem.discard(v)
                    self.logw.writerow([f"{now:.1f}", iid, v, "LEAVE_ARRIVED", ""])
            for v in vids:
                vx, vy = t.vehicle.getPosition(v)
                eu = ((vx - jx) ** 2 + (vy - jy) ** 2) ** 0.5
                key = (iid, v)
                prev = self.prev_dist.get(key)
                self.prev_dist[key] = eu
                if v in mem:
                    if eu >= common.LEAVE_DIST_M and prev is not None and eu > prev:
                        mem.discard(v)
                        self.logw.writerow([f"{now:.1f}", iid, v, "LEAVE", f"{eu:.1f}"])
                    continue
                d = self.routes.distance(v, iid)
                if d is None:
                    continue           # この交差点を通らない（または通過済み）
                eta = d / max(t.vehicle.getSpeed(v), 1.0)
                if eta < common.JOIN_ETA_SEC:
                    mem.add(v)
                    self.logw.writerow([f"{now:.1f}", iid, v, "JOIN", f"{eta:.1f}"])


def main():
    a = parse()
    common.sumo_home()
    import traci

    name = a.tag or f"{a.mode}_s{a.seed}"
    out = os.path.join(common.OUT_DIR, name)
    os.makedirs(out, exist_ok=True)
    cfg = os.path.join(common.SCENARIO_DIR, "scenario.sumocfg")
    cmd = [common.sumo_bin("sumo-gui" if a.gui else "sumo"), "-c", cfg, "--seed", str(a.seed),
           "--device.ssm.probability", "1", "--device.ssm.measures", "TTC PET DRAC",
           "--device.ssm.thresholds", "3.0 2.0 3.0", "--device.ssm.range", "50",
           "--device.ssm.file", os.path.join(out, "ssm.xml"),
           "--device.emissions.probability", "1",
           "--tripinfo-output", os.path.join(out, "tripinfo.xml"),
           "--collision.action", "warn", "--collision.check-junctions", "true",
           "--collision-output", os.path.join(out, "collisions.xml"),
           "--no-step-log", "true", "--no-warnings", "true"]
    if not a.no_fcd:
        cmd += ["--fcd-output", os.path.join(out, "fcd.xml"), "--fcd-output.acceleration", "true"]
    traci.start(cmd)

    with open(os.path.join(common.SCENARIO_DIR, "hazards.json"), encoding="utf-8") as f:
        hazards = sorted(json.load(f), key=lambda h: h["time"])
    junctions, jids = {}, {}
    with open(common.INTERSECTION_MAP, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["active"] == "1":
                junctions[r["intersectionId"]] = traci.junction.getPosition(r["junctionId"])
                jids[r["intersectionId"]] = r["junctionId"]
    routes = RouteIndex(traci, common.load_net(), jids)

    rng = random.Random(a.seed)
    gf = open(os.path.join(out, "group_log.csv"), "w", newline="", encoding="utf-8")
    gw = csv.writer(gf); gw.writerow(["t", "intersectionId", "vehicle", "event", "eta_or_dist"])
    hf = open(os.path.join(out, "hazard_log.csv"), "w", newline="", encoding="utf-8")
    hw = csv.writer(hf); hw.writerow(["t", "intersectionId", "hazard_vehicle", "event", "target", "detail"])
    groups = Groups(traci, junctions, routes, gw)

    active = []      # [{veh, until, iid}]
    pending = []     # 通知の遅延キュー [(deliver_t, hazard)]
    warned = {}      # 減速中の車 → 解除時刻
    hi = 0
    step_len = traci.simulation.getDeltaT()
    end_t = traci.simulation.getEndTime()
    while traci.simulation.getMinExpectedNumber() > 0 and traci.simulation.getTime() < end_t:
        traci.simulationStep()
        now = traci.simulation.getTime()
        if int(now / step_len) % 2 == 0:
            groups.update(now)

        # 急停止イベントの発生
        while hi < len(hazards) and now >= hazards[hi]["time"]:
            h = hazards[hi]; hi += 1
            lo, up = h["upstream_m"]
            cands = []
            for v in traci.vehicle.getIDList():
                d = routes.distance(v, h["intersectionId"])
                if d is not None and lo <= d <= up and traci.vehicle.getSpeed(v) > 5:
                    cands.append(v)
            if not cands:
                hw.writerow([f"{now:.1f}", h["intersectionId"], "", "SKIP_NO_VEHICLE", "", ""])
                continue
            v = rng.choice(cands)
            spd = traci.vehicle.getSpeed(v)
            traci.vehicle.setDecel(v, h["decel"])
            traci.vehicle.slowDown(v, 0.0, max(spd / h["decel"], 0.5))
            active.append({"veh": v, "until": now + h["stop_sec"] + spd / h["decel"], "iid": h["intersectionId"]})
            hw.writerow([f"{now:.1f}", h["intersectionId"], v, "SUDDEN_STOP", "", f"speed={spd:.1f}"])
            if a.mode == "ideal":
                pending.append((now + a.latency, {"veh": v, "iid": h["intersectionId"],
                                                  "until": active[-1]["until"]}))

        # 停止の維持と解除
        for h in list(active):
            if h["veh"] not in traci.vehicle.getIDList():
                active.remove(h); continue
            if now >= h["until"]:
                traci.vehicle.setSpeed(h["veh"], -1)
                traci.vehicle.setDecel(h["veh"], 4.5)
                active.remove(h)
                hw.writerow([f"{now:.1f}", h["iid"], h["veh"], "RESUME_HAZARD", "", ""])
            else:
                traci.vehicle.setSpeed(h["veh"], 0.0)

        # 理想V2V: 同じ交差点グループの後続車へ通知 → DECELERATE
        for item in list(pending):
            t_deliver, h = item
            if now < t_deliver:
                continue
            pending.remove(item)
            hv = h["veh"]
            if hv not in traci.vehicle.getIDList():
                continue
            h_edge, h_pos = traci.vehicle.getRoadID(hv), traci.vehicle.getLanePosition(hv)
            for m in groups.members[h["iid"]]:
                if m == hv or m not in traci.vehicle.getIDList():
                    continue
                d = traci.vehicle.getDrivingDistance(m, h_edge, h_pos)
                if d is None or d <= 0 or d > 1e6:
                    continue            # 後続ではない（前方・対向・別ルート）
                traci.vehicle.slowDown(m, min(a.warn_speed, traci.vehicle.getSpeed(m)), 3.0)
                traci.vehicle.setSpeed(m, min(a.warn_speed, traci.vehicle.getSpeed(m)))
                warned[m] = h["until"]
                hw.writerow([f"{now:.1f}", h["iid"], hv, "DECELERATE", m, f"gap={d:.1f}"])

        for m, until in list(warned.items()):
            if m not in traci.vehicle.getIDList():
                del warned[m]; continue
            if now >= until:
                traci.vehicle.setSpeed(m, -1)
                del warned[m]
                hw.writerow([f"{now:.1f}", "", "", "RESUME", m, ""])

    traci.close()
    gf.close(); hf.close()
    print(f"完了: {out}")


if __name__ == "__main__":
    main()
