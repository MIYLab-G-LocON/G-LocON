"""正式評価: 決めた条件の組み合わせを，乱数を変えて繰り返し実行する.

    python eval_run.py --plan quick            # 動作確認（2実行，数分）
    python eval_run.py --plan main             # 主: 従来G-LocONとの比較（60実行）
    python eval_run.py --plan params           # 副: 本システムの τ・ρ・δ を1つずつ変える（5実行）
    python eval_run.py --plan noise            # 補: 測位誤差の大きさ・変わり方（24実行）
    python eval_run.py --plan main --list      # 実行せず，条件の一覧だけ表示
    python eval_run.py --plan main --jobs 4    # 同時に4つ実行（PCのコア数に合わせる）

1実行 = compare_schemes.py を1回（SUMO を1回走らせ，その走行の上で全方式の接続を計算する）。
結果は results/eval/<plan>/<条件名>.csv に1実行ずつ保存する。途中で止めても，もう一度実行すれば
済んだ分は飛ばして続きから始める（やり直すときは --force，または該当の csv を消す）。

集計（表・グラフ）は eval_report.py。手順は EVALUATION.md。
"""
import argparse
import csv
import os
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

HERE = os.path.dirname(os.path.abspath(__file__))
RESULT_ROOT = os.path.join(HERE, "results", "eval")

# ---- 条件（ここを書き換えれば，実行する条件が変わる） ---------------------------------------------
PROPOSED = "15:100:100"                 # 本システムの既定: τ=15秒，ρ=100m，δ=100m
RADII = "100,150,200"                   # 従来G-LocON の半径 [m]（100m が元の既定）
SEEDS = [1, 2, 3, 4, 5]                 # 繰り返し（SUMO の乱数と，出発地・目的地の乱数を同時に変える）

MAPS = {
    # 地図名: (GLOCON_SCENARIO の値, 交通量 → 車両の発生間隔 [秒], 出発地と目的地の最小距離 [m], エリアの端を選ぶ重み)
    # 最小距離と重みは，それぞれの地図のシナリオを作ったときと同じ値（README「地図の切り替え」）
    "campus":   ("",         {"low": 3.0, "mid": 1.5, "high": 0.75}, 800, 3),
    "arterial": ("arterial", {"low": 1.5, "mid": 0.9, "high": 0.6}, 1500, 10),
}
# 測位誤差: 名前 → (標準偏差 [m], 誤差が続く時間 [秒]。0 = 毎秒ばらばら)
NOISE = {"gps0": (0, 0), "gps5slow": (5, 10), "gps5rand": (5, 0), "gps3slow": (3, 10), "gps3rand": (3, 0)}

# 本システムの条件を1つずつ変える（τ:ρ:δ）。既定は 15:100:100
PARAM_SETS = ["15:100:100",
              "30:100:100", "45:100:100",                    # τ
              "15:0:100", "15:50:100", "15:150:100",         # ρ
              "15:100:30", "15:100:60", "15:100:150"]        # δ


def plan_runs(plan):
    """[{name, map, traffic, period, noise, seed, etas, radii}]"""
    runs = []

    def add(m, traffic, noise, seed, etas=PROPOSED, radii=RADII):
        runs.append({"name": f"{m}_{traffic}_{noise}_s{seed}", "map": m, "traffic": traffic,
                     "period": MAPS[m][1][traffic], "noise": noise, "seed": seed, "etas": etas, "radii": radii})

    if plan == "quick":
        for noise in ("gps0", "gps5slow"):
            add("campus", "mid", noise, 1)
    elif plan == "main":
        for m in MAPS:
            for traffic in ("low", "mid", "high"):
                for noise in ("gps0", "gps5slow"):
                    for seed in SEEDS:
                        add(m, traffic, noise, seed)
    elif plan == "params":
        for seed in SEEDS:
            add("campus", "mid", "gps5slow", seed, etas=",".join(PARAM_SETS), radii="100")
    elif plan == "noise":
        for m in MAPS:
            for noise in ("gps3slow", "gps3rand", "gps5slow", "gps5rand"):
                for seed in SEEDS[:3]:
                    add(m, "mid", noise, seed)
    else:
        sys.exit(f"--plan は quick / main / params / noise のどれか（指定: {plan}）")
    return runs


def ensure_net(scenario):
    """地図（SUMO道路網）が無ければ作る（幹線道路の地図はリポジトリに入れていない）."""
    d = os.path.join(HERE, "scenario" + ("_" + scenario if scenario else ""))
    if os.path.exists(os.path.join(d, "area.net.xml")):
        return
    print(f"地図を作成します: {os.path.basename(d)}（初回のみ，数十秒）")
    env = dict(os.environ, GLOCON_SCENARIO=scenario)
    subprocess.run([sys.executable, "build_net.py"], cwd=HERE, env=env, check=True)


def run_one(r, plan, gui):
    out_csv = os.path.join(RESULT_ROOT, plan, r["name"] + ".csv")
    tag = f"eval_{plan}_{r['name']}"
    sigma, corr = NOISE[r["noise"]]
    cmd = [sys.executable, "compare_schemes.py", "--tag", tag,
           "--etas", r["etas"], "--radii", r["radii"],
           "--period", str(r["period"]), "--min-distance", str(MAPS[r["map"]][2]), "--fringe-factor", str(MAPS[r["map"]][3]),
           "--seed", str(r["seed"]), "--trip-seed", str(r["seed"]),
           "--gps-noise", str(sigma), "--gps-corr", str(corr)]
    if gui:
        cmd.append("--gui")
    env = dict(os.environ, GLOCON_SCENARIO=MAPS[r["map"]][0], PYTHONIOENCODING="utf-8")
    log = os.path.join(RESULT_ROOT, plan, "logs", r["name"] + ".log")
    t0 = time.time()
    with open(log, "w", encoding="utf-8") as f:
        f.write(" ".join(cmd) + "\n")
        f.flush()
        code = subprocess.run(cmd, cwd=HERE, env=env, stdout=f, stderr=subprocess.STDOUT).returncode
    src = os.path.join(HERE, "out", "compare_" + tag, "schemes.csv")
    if code != 0 or not os.path.exists(src):
        return r["name"], False, time.time() - t0, log
    # 条件の列を足して保存する（集計で使う）
    with open(src, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    head = ["plan", "run", "map", "traffic", "noise"]
    with open(out_csv + ".tmp", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, head + list(rows[0].keys()))
        w.writeheader()
        for row in rows:
            w.writerow({"plan": plan, "run": r["name"], "map": r["map"], "traffic": r["traffic"], "noise": r["noise"], **row})
    os.replace(out_csv + ".tmp", out_csv)
    shutil.rmtree(os.path.join(HERE, "out", "compare_" + tag), ignore_errors=True)   # 中間ファイル（数百MBになる）を消す
    return r["name"], True, time.time() - t0, log


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--plan", default="quick", help="quick / main / params / noise")
    ap.add_argument("--jobs", type=int, default=2, help="同時に実行する数（PCのコア数の半分くらいが目安）")
    ap.add_argument("--list", action="store_true", help="実行せず，条件の一覧だけ表示する")
    ap.add_argument("--force", action="store_true", help="済んだ分もやり直す")
    ap.add_argument("--gui", action="store_true", help="sumo-gui で走行を表示する（説明用。--jobs 1 になる）")
    ap.add_argument("--only", default="", help="条件名にこの文字列を含むものだけ実行する（例: campus_mid）")
    a = ap.parse_args()

    runs = [r for r in plan_runs(a.plan) if a.only in r["name"]]
    os.makedirs(os.path.join(RESULT_ROOT, a.plan, "logs"), exist_ok=True)
    todo = [r for r in runs if a.force or not os.path.exists(os.path.join(RESULT_ROOT, a.plan, r["name"] + ".csv"))]
    print(f"計画 {a.plan}: 全 {len(runs)} 実行，済み {len(runs) - len(todo)}，これから {len(todo)}")
    if a.list:
        print(f"{'条件名':<34}{'地図':<10}{'交通量':<6}{'発生間隔':>8}  {'測位誤差':<10}{'乱数':>4}  状態")
        for r in runs:
            done = r not in todo
            print(f"{r['name']:<36}{r['map']:<12}{r['traffic']:<8}{r['period']:>8}秒  {r['noise']:<12}{r['seed']:>4}  {'済み' if done else '未'}")
        return
    if not todo:
        print("すべて済んでいます。集計は python eval_report.py --plan", a.plan)
        return
    for sc in {MAPS[r["map"]][0] for r in todo}:
        ensure_net(sc)
    jobs = 1 if a.gui else max(1, a.jobs)
    t0, n_ok = time.time(), 0
    with ThreadPoolExecutor(max_workers=jobs) as ex:
        futs = [ex.submit(run_one, r, a.plan, a.gui) for r in todo]
        for i, fu in enumerate(as_completed(futs), 1):
            name, ok, sec, log = fu.result()
            n_ok += ok
            rest = (time.time() - t0) / i * (len(todo) - i)
            print(f"[{i}/{len(todo)}] {name}: {'完了' if ok else '失敗 → ' + log}（{sec / 60:.1f}分，残り約{rest / 60:.0f}分）", flush=True)
    print(f"終了: 成功 {n_ok} / {len(todo)}。結果 → {os.path.join(RESULT_ROOT, a.plan)}")
    print(f"集計（表・グラフ）: python eval_report.py --plan {a.plan}")


if __name__ == "__main__":
    main()
