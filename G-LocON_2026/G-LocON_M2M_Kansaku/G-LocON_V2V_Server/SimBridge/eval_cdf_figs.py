"""論文・報告用の CDF の図と数値を，eval_cdf.py の度数分布（cdf_hist.csv）から作る.

    python eval_cdf_figs.py                                  # 既定: results/eval_detail/main/report_cdf/cdf_hist.csv
    python eval_cdf_figs.py --hist <cdf_hist.csv> --out <フォルダ>

流れ（すべてこの PC で再現できる）:
    1. eval_run.py --detail          60 実行の生データ（接続の記録・毎秒の接続数）を出す
    2. eval_cdf.py                   生データから，値ごとの件数（度数分布）を数えて cdf_hist.csv に書く
    3. eval_cdf_figs.py（このファイル） 度数分布を小さい値から足し上げ，全件数で割って CDF にし，図を描く

CDF（累積分布）: 横軸の値「以下」になった割合を縦軸に描いたもの。
    例: 接続の継続時間の CDF で横軸 10 s の縦軸が 0.2 なら，「接続の 20% は 10 秒以下で切れた」。
    同時接続数の CDF で横軸 0 の縦軸は，「接続相手が 0 台だった時間の割合」。

出力（--out，既定 <cdf_hist.csv と同じフォルダ>/figs）:
    fig_cdf.pdf               論文の図（代表 2 条件，測位誤差 5 m）
    rep_cdf_dur_all.pdf       報告用：接続の継続時間（全 6 条件，測位誤差 5 m）
    rep_cdf_peers_all.pdf     報告用：同時接続数（全 6 条件，測位誤差 5 m）
    rep_cdf_gps.pdf           報告用：測位誤差の有無の比較（交通量多の 2 条件）
    cdf_numbers.csv           論文・報告に書いた数値（中央値，10 s 以下・60 s 以上の割合，0 台の時間，90 パーセンタイル）

必要なもの: matplotlib（pip install matplotlib）。numpy・pandas は不要。
"""
import argparse
import csv
import os
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_HIST = os.path.join(HERE, "results", "eval_detail", "main", "report_cdf", "cdf_hist.csv")

PROPOSED = "eta_t15_j100_d100"          # 図に描く本システムの条件（--proposed で変える。例 eta_t20_j100_d100）


def make_lines(proposed):
    return {proposed: ("提案方式", "k", "-", 1.3),
            "dist_r100": ("従来 100 m", "#555555", (0, (4, 2)), 0.9),
            "dist_r150": ("従来 150 m", "#9a9a9a", "-", 0.9),
            "dist_r200": ("従来 200 m", "#555555", (0, (1, 1.2)), 0.9)}


LINES = make_lines(PROPOSED)
CONDS = [("campus_low", "大学周辺・交通量少"), ("campus_mid", "大学周辺・交通量中"), ("campus_high", "大学周辺・交通量多"),
         ("arterial_low", "幹線道路・交通量少"), ("arterial_mid", "幹線道路・交通量中"), ("arterial_high", "幹線道路・交通量多")]
XLABEL = {"conn_dur": "接続の継続時間 [s]", "peers": "同時接続数 [台]"}
W = 3.35  # 論文の 1 段の幅 [inch]


def read_hist(path):
    """cdf_hist.csv -> {(条件, 指標, 方式): Counter{値: 件数}}"""
    hist = {}
    with open(path, encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            hist.setdefault((r["condition"], r["metric"], r["scheme"]), Counter())[int(float(r["value"]))] += int(r["count"])
    return hist


def cdf(h):
    """度数分布 -> CDF の点列（値, その値以下の割合）. 小さい値から件数を足し上げ，全件数で割る."""
    n = sum(h.values())
    xs, ys, c = [], [], 0
    for v in sorted(h):
        c += h[v]
        xs.append(v)
        ys.append(c / n)
    return xs, ys


def quantile(h, q):
    """分位点（numpy.quantile の既定と同じ線形補間）. eval_cdf.py と同じ計算."""
    n = sum(h.values())
    pos = q * (n - 1)
    lo, hi = int(pos), min(int(pos) + 1, n - 1)
    vlo = vhi = None
    c = 0
    for v in sorted(h):
        c += h[v]
        if vlo is None and c > lo:
            vlo = v
        if c > hi:
            vhi = v
            break
    return vlo + (vhi - vlo) * (pos - lo)


def setup_font(plt, matplotlib):
    names = {x.name for x in matplotlib.font_manager.fontManager.ttflist}
    for f in ("Noto Sans CJK JP", "Yu Gothic", "Meiryo", "MS Gothic", "IPAexGothic"):
        if f in names:
            plt.rcParams["font.family"] = f
            return f
    return None


def style(ax, m, xlabel, fs_label, fs_tick):
    if m == "conn_dur":
        ax.set_xlim(0, 180); ax.set_xticks([0, 30, 60, 90, 120, 150, 180])
    else:
        ax.set_xlim(0, None)
    ax.set_ylim(0, 1.0); ax.set_yticks([0, 0.2, 0.4, 0.6, 0.8, 1.0])
    ax.set_yticklabels(["0", "0.2", "0.4", "0.6", "0.8", "1"])
    ax.set_xlabel(xlabel, fontsize=fs_label, linespacing=1.25)
    ax.tick_params(length=2, width=0.5, labelsize=fs_tick)
    ax.grid(lw=0.3, color="#cccccc"); ax.set_axisbelow(True)


def fig_paper(plt, hist, out):
    """論文の図: 代表 2 条件（測位誤差 5 m）の接続の継続時間と同時接続数."""
    conds = (("campus_mid_gps5slow", "大学周辺・交通量中"), ("arterial_high_gps5slow", "幹線道路・交通量多"))
    fig, axes = plt.subplots(2, 2, figsize=(W, 2.75))
    for i, (c, title) in enumerate(conds):
        for j, m in enumerate(("conn_dur", "peers")):
            ax = axes[i][j]
            for s, (lab, col, ls, lw) in LINES.items():
                x, y = cdf(hist[(c, m, s)])
                ax.step(x, y, where="post", color=col, ls=ls, lw=lw, label=lab)
            style(ax, m, f"{XLABEL[m]}\n({'abcd'[2 * i + j]}) {title}", 6.6, 6.3)
            if j == 0:
                ax.set_ylabel("累積確率", fontsize=6.8)
    h, l = axes[0][0].get_legend_handles_labels()
    fig.legend(h, l, loc="upper center", ncol=4, fontsize=6.3, frameon=False, bbox_to_anchor=(0.53, 1.0),
               handlelength=2.0, columnspacing=1.0)
    fig.subplots_adjust(wspace=0.28, hspace=0.62)
    fig.savefig(os.path.join(out, "fig_cdf.pdf"), bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


def fig_all(plt, hist, out, m, name):
    """報告用: 全 6 条件（測位誤差 5 m）."""
    fig, axes = plt.subplots(2, 3, figsize=(6.3, 4.0))
    for k, (c, title) in enumerate(CONDS):
        ax = axes[k // 3][k % 3]
        for s, (lab, col, ls, lw) in LINES.items():
            x, y = cdf(hist[(f"{c}_gps5slow", m, s)])
            ax.step(x, y, where="post", color=col, ls=ls, lw=lw, label=lab)
        style(ax, m, f"{XLABEL[m]}\n({'abcdef'[k]}) {title}", 7.5, 7)
        if k % 3 == 0:
            ax.set_ylabel("累積確率", fontsize=7.5)
    h, l = axes[0][0].get_legend_handles_labels()
    fig.legend(h, l, loc="upper center", ncol=4, fontsize=7.5, frameon=False, bbox_to_anchor=(0.5, 1.02))
    fig.subplots_adjust(wspace=0.25, hspace=0.55)
    fig.savefig(os.path.join(out, name), bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


def fig_gps(plt, hist, out):
    """報告用: 測位誤差の有無による接続の継続時間の比較（交通量多の 2 条件）."""
    conds = (("campus_high", "大学周辺・交通量多"), ("arterial_high", "幹線道路・交通量多"))
    sch = {PROPOSED: ("提案方式", "k", 1.4), "dist_r100": ("従来 100 m", "#8a8a8a", 1.1)}
    fig, axes = plt.subplots(1, 2, figsize=(6.3, 2.3))
    for k, (c, title) in enumerate(conds):
        ax = axes[k]
        for s, (lab, col, lw) in sch.items():
            for g, glab, ls in (("gps0", "誤差なし", "-"), ("gps5slow", "誤差5 m", (0, (4, 2)))):
                x, y = cdf(hist[(f"{c}_{g}", "conn_dur", s)])
                ax.step(x, y, where="post", color=col, ls=ls, lw=lw, label=f"{lab}（{glab}）")
        style(ax, "conn_dur", f"{XLABEL['conn_dur']}\n({'ab'[k]}) {title}", 7.5, 7)
        if k == 0:
            ax.set_ylabel("累積確率", fontsize=7.5)
    h, l = axes[0].get_legend_handles_labels()
    fig.legend(h, l, loc="upper center", ncol=4, fontsize=7.5, frameon=False, bbox_to_anchor=(0.5, 1.06))
    fig.subplots_adjust(wspace=0.22)
    fig.savefig(os.path.join(out, "rep_cdf_gps.pdf"), bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


def write_numbers(hist, out):
    """論文・報告に書いた数値を，度数分布から計算して CSV に出す."""
    rows = []
    for c in sorted({k[0] for k in hist}):
        for s in LINES:
            d, p = hist.get((c, "conn_dur", s)), hist.get((c, "peers", s))
            if not d or not p:
                continue
            nd, npr = sum(d.values()), sum(p.values())
            rows.append({
                "condition": c, "scheme": s,
                "connections": nd,
                "dur_median_s": round(quantile(d, 0.5), 1),
                "dur_le10s_pct": round(100 * sum(v for k, v in d.items() if k <= 10) / nd, 1),
                "dur_ge60s_pct": round(100 * sum(v for k, v in d.items() if k >= 60) / nd, 1),
                "peers_zero_pct": round(100 * p.get(0, 0) / npr, 1),
                "peers_median": round(quantile(p, 0.5), 1),
                "peers_p90": round(quantile(p, 0.9), 1),
            })
    with open(os.path.join(out, "cdf_numbers.csv"), "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    return rows


def main():
    global PROPOSED, LINES
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hist", default=DEFAULT_HIST, help="eval_cdf.py が出した cdf_hist.csv")
    ap.add_argument("--out", default="", help="出力先（既定 <cdf_hist.csv のフォルダ>/figs）")
    ap.add_argument("--proposed", default=PROPOSED,
                    help="図に描く本システムの条件（既定 eta_t15_j100_d100。τ=20 なら eta_t20_j100_d100）")
    a = ap.parse_args()
    PROPOSED, LINES = a.proposed, make_lines(a.proposed)
    out = a.out or os.path.join(os.path.dirname(os.path.abspath(a.hist)), "figs")
    os.makedirs(out, exist_ok=True)

    hist = read_hist(a.hist)
    rows = write_numbers(hist, out)
    print(f"数値: {os.path.join(out, 'cdf_numbers.csv')}（{len(rows)} 行）")

    try:
        import matplotlib
    except ImportError:
        print("matplotlib が無いので図は描きません（python -m pip install matplotlib で入れられます）")
        return
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    font = setup_font(plt, matplotlib)
    plt.rcParams.update({"font.size": 7.5, "axes.linewidth": 0.6, "pdf.fonttype": 3})
    fig_paper(plt, hist, out)
    fig_all(plt, hist, out, "conn_dur", "rep_cdf_dur_all.pdf")
    fig_all(plt, hist, out, "peers", "rep_cdf_peers_all.pdf")
    fig_gps(plt, hist, out)
    print(f"図: {out}（フォント: {font}）")
    print("  fig_cdf.pdf / rep_cdf_dur_all.pdf / rep_cdf_peers_all.pdf / rep_cdf_gps.pdf")


if __name__ == "__main__":
    main()
