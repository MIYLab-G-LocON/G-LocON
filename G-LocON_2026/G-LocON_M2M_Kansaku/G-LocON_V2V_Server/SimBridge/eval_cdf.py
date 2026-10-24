"""生データ（eval_run.py --detail）から，接続時間・接続数の CDF（累積分布）を作る.

    python eval_cdf.py --root results/eval_detail --plan main
    python eval_cdf.py --root results/eval_detail --plan main --only campus_mid
    python eval_cdf.py --from-hist <cdf_hist.csv> --out <フォルダ>     # 数えた結果から図だけ描く（別のPCで描くとき）

CDF: 横軸の値「以下」になる割合を縦軸に描いたグラフ。例えば接続時間の CDF で (20秒, 0.5) は
「接続の半分は 20 秒以下で切れた」という意味。平均だけでは見えない分布の形（短い接続がどれだけあるか等）がわかる。

1. 数える（Python の標準機能だけで動く。numpy・matplotlib は不要）
   接続時間（秒）・接続数（台）は整数なので，値ごとの件数（度数分布）を数えれば CDF が誤差なく作れる。
2. 描く（matplotlib があるときだけ。無ければ 1 だけ行い，図は --from-hist で別の環境で描ける）

出力 <root>/<plan>/report_cdf/:
    cdf_hist.csv                                 度数分布（条件・指標・方式・値ごとの件数）。図はこれだけから描ける
    cdf_values.csv                               方式ごとの件数・平均・分位点（5〜95%）。表に使う
    cdf_conn_dur_<地図>_<交通量>_<測位誤差>.png   接続1回が続いた時間の CDF（乱数5回分をまとめたもの）
    cdf_peers_<...>.png                          各車が各時点でつながっている相手の数の CDF（1秒ごと・1台ごとの値）

接続時間は，走行の終わりまで続いていた接続（open=1）を含める（schemes.csv の conn_sec_median と同じ数え方）。
"""
import argparse
import csv
import glob
import gzip
import os
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
METRICS = (("conn_dur", "接続1回が続いた時間 [秒]"), ("peers", "同時につながっている相手の数 [台]"))
QS = [0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95]


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


def quantile(hist, q):
    """度数分布 {値: 件数} の分位点（numpy.quantile の既定と同じ線形補間）."""
    n = sum(hist.values())
    pos = q * (n - 1)
    lo, hi = int(pos), min(int(pos) + 1, n - 1)
    vlo = vhi = None
    c = 0
    for v in sorted(hist):
        c += hist[v]
        if vlo is None and c > lo:
            vlo = v
        if c > hi:
            vhi = v
            break
    return vlo + (vhi - vlo) * (pos - lo)


def count(raw, runs):
    """{(条件, 指標, 方式): Counter(値 -> 件数)}, {条件: 実行数}"""
    hist, nruns = {}, {}
    for r in runs:
        cond = cond_of(r)[0]
        nruns[cond] = nruns.get(cond, 0) + 1
        d = os.path.join(raw, r)
        for fn in glob.glob(os.path.join(d, "connections_*.csv.gz")):
            name = os.path.basename(fn)[len("connections_"):-len(".csv.gz")]
            h = hist.setdefault((cond, "conn_dur", name), Counter())
            with gzip.open(fn, "rt", encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    h[int(round(float(row["dur"])))] += 1
        with gzip.open(os.path.join(d, "timeseries.csv.gz"), "rt", encoding="utf-8") as f:
            rd = csv.reader(f)
            head = next(rd)
            cols = [(i, hist.setdefault((cond, "peers", c[2:]), Counter())) for i, c in enumerate(head) if c.startswith("n_")]
            for row in rd:
                for i, h in cols:
                    h[int(row[i])] += 1
        print(f"  {r}", flush=True)
    return hist, nruns


def write_tables(out, hist, nruns):
    with open(os.path.join(out, "cdf_hist.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["condition", "runs", "metric", "scheme", "value", "count"])
        for (cond, m, s), h in sorted(hist.items()):
            for v in sorted(h):
                w.writerow([cond, nruns[cond], m, s, v, h[v]])
    rows = []
    for (cond, m, s), h in sorted(hist.items()):
        n = sum(h.values())
        if not n:
            continue
        rows.append({"condition": cond, "runs": nruns[cond], "metric": m, "scheme": s, "n": n,
                     "mean": round(sum(v * c for v, c in h.items()) / n, 2),
                     **{f"p{int(q * 100)}": quantile(h, q) for q in QS}})
    with open(os.path.join(out, "cdf_values.csv"), "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)


def read_hist(path):
    hist, nruns = {}, {}
    with open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            hist.setdefault((r["condition"], r["metric"], r["scheme"]), Counter())[int(r["value"])] += int(r["count"])
            nruns[r["condition"]] = int(r["runs"])
    return hist, nruns


def draw(out, hist, nruns):
    try:
        import matplotlib
    except ImportError:
        print("（matplotlib が無いので図は描きません。cdf_hist.csv から python eval_cdf.py --from-hist で描けます）")
        return
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    for f in ("Yu Gothic", "Meiryo", "MS Gothic", "Noto Sans CJK JP", "IPAexGothic", "IPAGothic"):
        if any(f == x.name for x in matplotlib.font_manager.fontManager.ttflist):
            plt.rcParams["font.family"] = f
            break
    for cond in sorted(nruns):
        for m, xlabel in METRICS:
            names = sorted((s for c, mm, s in hist if c == cond and mm == m), key=lambda n: (not n.startswith("eta"), n))
            fig, ax = plt.subplots(figsize=(5.2, 3.6))
            for s in names:
                h = hist[(cond, m, s)]
                n = sum(h.values())
                xs, ys, c = [], [], 0
                for v in sorted(h):
                    c += h[v]
                    xs.append(v)
                    ys.append(c / n)
                ax.step(xs, ys, where="post", label=scheme_label(s), lw=1.6 if s.startswith("eta") else 1.1)
            ax.set_xlabel(xlabel)
            ax.set_ylabel("累積割合")
            ax.set_ylim(0, 1.0)
            if m == "conn_dur":
                ax.set_xscale("log")
            ax.grid(alpha=0.3)
            ax.legend(fontsize=7, loc="lower right")
            ax.set_title(f"{cond}（乱数{nruns[cond]}回）", fontsize=9)
            fig.tight_layout()
            fig.savefig(os.path.join(out, f"cdf_{m}_{cond}.png"), dpi=200)
            plt.close(fig)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.join(HERE, "results", "eval_detail"))
    ap.add_argument("--plan", default="main")
    ap.add_argument("--only", default="", help="条件名にこの文字列を含むものだけ")
    ap.add_argument("--from-hist", default="", help="数えた結果（cdf_hist.csv）から図だけ描く")
    ap.add_argument("--out", default="", help="出力先（既定 <root>/<plan>/report_cdf）")
    a = ap.parse_args()
    out = a.out or os.path.join(a.root, a.plan, "report_cdf")
    os.makedirs(out, exist_ok=True)

    if a.from_hist:
        hist, nruns = read_hist(a.from_hist)
    else:
        raw = os.path.join(a.root, a.plan, "raw")
        runs = sorted(d for d in os.listdir(raw) if a.only in d and os.path.isdir(os.path.join(raw, d)))
        if not runs:
            raise SystemExit(f"生データがありません: {raw}")
        print(f"生データを数えます（{len(runs)} 実行。数分かかります）")
        hist, nruns = count(raw, runs)
    write_tables(out, hist, nruns)
    draw(out, hist, nruns)
    print(f"→ {out}")


if __name__ == "__main__":
    main()
