"""生データ（eval_run.py --detail）から，接続時間・接続数の CDF（累積分布）を描く.

    python eval_cdf.py --root results/eval_detail --plan main
    python eval_cdf.py --root results/eval_detail --plan main --only campus_mid

CDF: 横軸の値「以下」になる割合を縦軸に描いたグラフ。例えば接続時間の CDF で (20秒, 0.5) は
「接続の半分は 20 秒以下で切れた」という意味。平均だけでは見えない分布の形（短い接続がどれだけあるか等）がわかる。

出力 <root>/<plan>/report_cdf/:
    cdf_conn_dur_<地図>_<交通量>_<測位誤差>.png   接続1回が続いた時間の CDF（乱数5回分をまとめたもの）
    cdf_peers_<...>.png                          各車が各時点でつながっている相手の数の CDF（1秒ごと・1台ごとの値）
    cdf_values.csv                               グラフの元の数値（方式ごとの分位点。Excel で描き直すとき用）

接続時間は，走行の終わりまで続いていた接続（open=1）を含める（schemes.csv の conn_sec_median と同じ数え方）。
"""
import argparse
import csv
import glob
import gzip
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))


def scheme_label(name):
    if name.startswith("eta_"):
        t, j, d = name[4:].split("_")
        return f"本システム（τ={t[1:]}s, ρ={j[1:]}m, δ={d[1:]}m）"
    if name.startswith("dist_r"):
        return f"従来G-LocON（半径{name[6:]}m）"
    return name


def cond_of(run):
    """campus_mid_gps5slow_s1 -> (campus_mid_gps5slow, 1)"""
    base, s = run.rsplit("_s", 1)
    return base, int(s)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.join(HERE, "results", "eval_detail"))
    ap.add_argument("--plan", default="main")
    ap.add_argument("--only", default="", help="条件名にこの文字列を含むものだけ")
    a = ap.parse_args()

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    for f in ("Yu Gothic", "Meiryo", "MS Gothic", "Noto Sans CJK JP", "IPAexGothic", "IPAGothic"):
        if any(f == x.name for x in matplotlib.font_manager.fontManager.ttflist):
            plt.rcParams["font.family"] = f
            break

    raw = os.path.join(a.root, a.plan, "raw")
    runs = sorted(d for d in os.listdir(raw) if a.only in d and os.path.isdir(os.path.join(raw, d)))
    if not runs:
        raise SystemExit(f"生データがありません: {raw}")
    out = os.path.join(a.root, a.plan, "report_cdf")
    os.makedirs(out, exist_ok=True)

    conds = {}
    for r in runs:
        conds.setdefault(cond_of(r)[0], []).append(r)

    qs = [0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95]
    rows = []
    for cond, rs in sorted(conds.items()):
        dur, peers = {}, {}
        for r in rs:
            d = os.path.join(raw, r)
            for fn in glob.glob(os.path.join(d, "connections_*.csv.gz")):
                name = os.path.basename(fn)[len("connections_"):-len(".csv.gz")]
                with gzip.open(fn, "rt", encoding="utf-8") as f:
                    dur.setdefault(name, []).append(np.array([float(r["dur"]) for r in csv.DictReader(f)]))
            with gzip.open(os.path.join(d, "timeseries.csv.gz"), "rt", encoding="utf-8") as f:
                rd = csv.reader(f)
                head = next(rd)
                cols = [(i, c[2:]) for i, c in enumerate(head) if c.startswith("n_")]
                vals = {n: [] for _, n in cols}
                for row in rd:
                    for i, n in cols:
                        vals[n].append(int(row[i]))
            for n, v in vals.items():
                peers.setdefault(n, []).append(np.array(v))
        names = sorted(dur, key=lambda n: (not n.startswith("eta"), n))
        for kind, data, xlabel in (("conn_dur", dur, "接続1回が続いた時間 [秒]"),
                                   ("peers", peers, "同時につながっている相手の数 [台]")):
            fig, ax = plt.subplots(figsize=(5.2, 3.6))
            for n in names:
                v = np.sort(np.concatenate(data[n]))
                if len(v) == 0:
                    continue
                y = np.arange(1, len(v) + 1) / len(v)
                ax.step(v, y, where="post", label=scheme_label(n), lw=1.6 if n.startswith("eta") else 1.1)
                rows.append({"condition": cond, "runs": len(rs), "metric": kind, "scheme": n, "n": len(v),
                             "mean": round(float(v.mean()), 2),
                             **{f"p{int(q * 100)}": float(np.quantile(v, q)) for q in qs}})
            ax.set_xlabel(xlabel)
            ax.set_ylabel("累積割合")
            ax.set_ylim(0, 1.0)
            if kind == "conn_dur":
                ax.set_xscale("log")
            ax.grid(alpha=0.3)
            ax.legend(fontsize=7, loc="lower right")
            ax.set_title(f"{cond}（乱数{len(rs)}回）", fontsize=9)
            fig.tight_layout()
            fig.savefig(os.path.join(out, f"cdf_{kind}_{cond}.png"), dpi=200)
            plt.close(fig)
        print(f"{cond}: {len(rs)} 実行")
    with open(os.path.join(out, "cdf_values.csv"), "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(f"→ {out}")


if __name__ == "__main__":
    main()
