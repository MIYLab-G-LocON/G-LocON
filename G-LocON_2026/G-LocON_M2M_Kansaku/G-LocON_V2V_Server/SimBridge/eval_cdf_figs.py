"""論文・報告用の CDF の図と数値を，eval_cdf.py の度数分布（cdf_hist.csv）から作る.
論文の棒グラフ（制御メッセージ数・判断余裕時間の確保率）も，eval_report.py の all_runs.csv から作る.

    python eval_cdf_figs.py                                  # 既定: results/eval_detail/main/report_cdf/cdf_hist.csv
    python eval_cdf_figs.py --hist <cdf_hist.csv> --out <フォルダ>
    python eval_cdf_figs.py --hist <cdf_hist.csv> --runs <all_runs.csv>   # all_runs.csv の場所を指定

グラフの決まり（研究室の決まり。2026/11/17）:
    - 枠で囲む（上下左右の黒い線）。縦軸は左，横軸は下。目盛りは枠の内側，縦軸の数値は 0.0〜1.0 と小数1桁でそろえる
    - 線・文字・枠は黒か色。灰色は使わない（PDF・印刷で読みにくい）。線は結果まとめのグラフと同じ色の実線
    - 補助線（格子）は黒の細い点線で，主目盛りにだけ引く（棒グラフは横線だけ）
    - 凡例はグラフの枠の中
    - 軸は 0 から。ただし接続の継続時間の CDF は対数の軸（0 を置けない）なので 1 s から
      （接続時間は 1 秒刻みで，最短が 1 s のため，切れるデータは無い）

流れ（すべてこの PC で再現できる）:
    1. eval_run.py --detail          60 実行の生データ（接続の記録・毎秒の接続数）を出す
    2. eval_cdf.py                   生データから，値ごとの件数（度数分布）を数えて cdf_hist.csv に書く
    3. eval_cdf_figs.py（このファイル） 度数分布を小さい値から足し上げ，全件数で割って CDF にし，図を描く

CDF（累積分布）: 横軸の値「以下」になった割合を縦軸に描いたもの。
    例: 接続の継続時間の CDF で横軸 10 s の縦軸が 0.2 なら，「接続の 20% は 10 秒以下で切れた」。
    同時接続数の CDF で横軸 0 の縦軸は，「接続相手が 0 台だった時間の割合」。

出力（--out，既定 <cdf_hist.csv と同じフォルダ>/figs）:
    fig_cdf.pdf               論文の図（代表 2 条件，測位誤差 5 m）。接続の継続時間は対数の軸
    fig_bars.pdf              論文の図：制御メッセージ数と判断余裕時間の確保率を 1 枚に（全 6 条件，測位誤差 5 m。all_runs.csv がある時）
    fig_ctrl.pdf / fig_margin.pdf  上の 2 つを別々の図にしたもの（2 つの地図を横に並べる）
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
    """方式 -> (凡例, 色, 線の種類, 線の太さ). 結果まとめ（正式評価_結果まとめ.html）のグラフと同じ見た目:
    すべて実線，提案=青（少し太く），従来 100/150/200 m=橙/緑/赤."""
    return {proposed: ("提案方式", "#1f77b4", "-", 1.2),
            "dist_r100": ("従来 100 m", "#ff7f0e", "-", 0.8),
            "dist_r150": ("従来 150 m", "#2ca02c", "-", 0.8),
            "dist_r200": ("従来 200 m", "#d62728", "-", 0.8)}


LINES = make_lines(PROPOSED)
CONDS = [("campus_low", "大学周辺・交通量少"), ("campus_mid", "大学周辺・交通量中"), ("campus_high", "大学周辺・交通量多"),
         ("arterial_low", "幹線道路・交通量少"), ("arterial_mid", "幹線道路・交通量中"), ("arterial_high", "幹線道路・交通量多")]
XLABEL = {"conn_dur": "接続の継続時間 [s]", "peers": "同時接続数 [台]"}
W = 3.35  # 論文の 1 段の幅 [inch]
H2 = 3.0   # 論文の 2×2 の図の高さ [inch]（CDF と棒グラフで同じ大きさにする）
FS_LABEL, FS_TICK, FS_LEGEND = 7.5, 7.0, 6.2   # 論文の図の文字の大きさ [pt]（本文 9 pt で読める大きさ）
DUR_MAX = 1000.0  # 接続の継続時間の軸の右端 [s]（線が無いときだけ使う）


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


def frame(ax, fs_tick, grid="both"):
    """枠（上下左右の黒い線）と黒い目盛り. 補助線（格子）は灰色ではなく黒の細い点線で，主目盛りにだけ引く
    （grid: "both" = 縦横，"y" = 横線だけ（棒グラフ））."""
    for sp in ax.spines.values():
        sp.set_visible(True); sp.set_color("k"); sp.set_linewidth(0.6)
    # 目盛りは枠の内側（他の論文の閉じたグラフと同じ）
    ax.tick_params(which="both", direction="in", colors="k", length=2.5, width=0.5, labelsize=fs_tick,
                   top=False, right=False, pad=2.5)
    ax.tick_params(which="minor", length=1.5, width=0.4)
    ax.grid(False)
    ax.grid(True, which="major", axis=grid, color="k", lw=0.3, ls=(0, (1, 2)))
    ax.set_axisbelow(True)


def legend_in(ax, loc, fs, ncol=1, **kw):
    """凡例をグラフの枠の中に置く（黒い細枠・白地）."""
    opt = dict(loc=loc, ncol=ncol, fontsize=fs, frameon=True, fancybox=False, framealpha=1.0,
               edgecolor="k", borderpad=0.3, labelspacing=0.2, handlelength=2.2, handletextpad=0.4,
               columnspacing=0.8, borderaxespad=0.3)
    opt.update(kw)
    lg = ax.legend(**opt)
    lg.get_frame().set_linewidth(0.5)
    return lg


def _hits(ax, bb):
    """凡例の枠 bb（画面の座標）に，グラフの線・棒・誤差棒が重なるか."""
    for ln in ax.get_lines():
        path = ln.get_transform().transform_path(ln.get_path())
        if path.intersects_bbox(bb, filled=False):
            return True
    for p in ax.patches:
        if p.get_window_extent().overlaps(bb):
            return True
    for c in ax.collections:
        for path in c.get_paths():
            if c.get_transform().transform_path(path).intersects_bbox(bb, filled=False):
                return True
    return False


def legend_free(ax, locs, fs, warn=True, **kw):
    """凡例を，グラフの線や棒にかぶらない場所に置く（locs の順に試し，最初に空いていた所）.
    戻り値: 空いた場所が見つかったか（見つからなければ locs の先頭に置く）."""
    from matplotlib.transforms import Bbox
    renderer = ax.figure.canvas.get_renderer()
    for loc in locs:
        lg = legend_in(ax, loc, fs, **kw)
        e = lg.get_window_extent(renderer)
        bb = Bbox.from_extents(e.x0 - 4, e.y0 - 4, e.x1 + 4, e.y1 + 4)   # 線の太さと少しの隙間の分だけ広げる
        if not _hits(ax, bb):
            return True
        lg.remove()
    legend_in(ax, locs[0], fs, **kw)
    if warn:
        print(f"  注意: 凡例が線にかぶらない場所が見つからなかった（{ax.get_xlabel().splitlines()[-1]}）")
    return False


def step_xy(x, y):
    """階段状の線（値が変わる所で縦に上がる）の点列. 凡例の重なりを確かめられるよう，ax.step ではなく折れ線で描く."""
    xs, ys = [], []
    for i in range(len(x)):
        if i:
            xs.append(x[i]); ys.append(y[i - 1])
        xs.append(x[i]); ys.append(y[i])
    return xs, ys


CDF_LOCS = ("lower right", "upper left", "center right", "lower left")


def style(ax, m, xlabel, fs_label, fs_tick, dur_max=None):
    from matplotlib.ticker import LogFormatterMathtext, LogLocator, MaxNLocator, NullFormatter
    if m == "conn_dur":
        # 対数の軸（結果まとめと同じ。目盛りは 10^0, 10^1, ...）。0 は置けないので，最短の 1 s から。
        # 右端はそのグラフの最長の接続の少し先（結果まとめと同じく，グラフごとに決める）
        if dur_max is None:
            xs = [x for ln in ax.get_lines() for x in ln.get_xdata()]
            # 最長の接続の少し先の，切りのよい値（1000, 2000, 5000, ...）。右下に凡例を置く余白にもなる
            need = max(xs) * 1.3 if xs else DUR_MAX
            dur_max = next(v for v in (1000, 2000, 5000, 10000, 20000, 50000, 1e9) if v >= need)
        ax.set_xscale("log")
        ax.set_xlim(1, dur_max)
        ax.xaxis.set_major_locator(LogLocator(base=10, numticks=12))   # 10^0, 10^1, ... をすべて出す
        ax.xaxis.set_major_formatter(LogFormatterMathtext(base=10))
        ax.xaxis.set_minor_locator(LogLocator(base=10, subs=range(2, 10)))
        ax.xaxis.set_minor_formatter(NullFormatter())
    else:
        ax.set_xlim(0, None)
        ax.xaxis.set_major_locator(MaxNLocator(nbins=4, steps=[1, 2, 2.5, 5, 10], integer=True))
    ax.set_ylim(0, 1.0); ax.set_yticks([0, 0.2, 0.4, 0.6, 0.8, 1.0])
    ax.set_yticklabels(["0.0", "0.2", "0.4", "0.6", "0.8", "1.0"])
    ax.set_xlabel(xlabel, fontsize=fs_label, linespacing=1.25)
    frame(ax, fs_tick)


def fig_paper(plt, hist, out):
    """論文の図: 代表 2 条件（測位誤差 5 m）の接続の継続時間（対数の軸）と同時接続数."""
    conds = (("campus_mid_gps5slow", "大学周辺・交通量中"), ("arterial_high_gps5slow", "幹線道路・交通量多"))
    fig, axes = plt.subplots(2, 2, figsize=(W, H2))
    for i, (c, title) in enumerate(conds):
        for j, m in enumerate(("conn_dur", "peers")):
            ax = axes[i][j]
            for s, (lab, col, ls, lw) in LINES.items():
                x, y = cdf(hist[(c, m, s)])
                ax.plot(*step_xy(x, y), color=col, ls=ls, lw=lw, label=lab)
            style(ax, m, f"{XLABEL[m]}\n({'abcd'[2 * i + j]}) {title}", FS_LABEL, FS_TICK)
            if j == 0:
                ax.set_ylabel("累積確率", fontsize=FS_LABEL)
    fig.subplots_adjust(wspace=0.3, hspace=0.62)
    for ax in axes.flat:      # 凡例はすべてのグラフに。線にかぶらない場所に置く（配置が決まってから調べる）
        legend_free(ax, CDF_LOCS, FS_LEGEND - 1.0, handlelength=1.2, handletextpad=0.3, borderpad=0.2,
                    labelspacing=0.1)
    fig.savefig(os.path.join(out, "fig_cdf.pdf"), bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


MAPS = (("campus", "大学周辺"), ("arterial", "幹線道路を含む"))
TRAFFIC = (("low", "少"), ("mid", "中"), ("high", "多"))
BARS = (("ctrl_per_veh_min", "制御メッセージ数\n[回/台・分]"), ("met_margin_es", "判断余裕時間の\n確保率 [%]"))


def read_runs(path):
    """all_runs.csv -> {(地図, 交通量, 測位誤差, 方式, 指標): [各乱数の値]}"""
    res = {}
    with open(path, encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            for col, _ in BARS:
                if r.get(col, "") != "":
                    res.setdefault((r["map"], r["traffic"], r["noise"], r["scheme"], col), []).append(float(r[col]))
    return res


def mean_sd(v):
    m = sum(v) / len(v)
    sd = (sum((x - m) ** 2 for x in v) / (len(v) - 1)) ** 0.5 if len(v) > 1 else 0.0
    return m, sd


def nice_top(v):
    """軸の上端（凡例を置く余白を含めて，切りのよい値）."""
    for step in (10, 20, 25, 50, 100):
        if v / step <= 8:
            return step * int(-(-v // step)), step
    return 100 * int(-(-v // 100)), 100


def bar_top(runs, col, noise, room):
    """縦軸の上端と目盛り間隔. room: 凡例を置く余白の倍率."""
    if col == "met_margin_es":
        return 140, 20      # 上の 100〜140 % は凡例を置く余白（目盛りもそこまで振る）
    mx = max(m + sd for (mp, t, n, s, c), v in runs.items() if c == col and n == noise and s in LINES
             for m, sd in [mean_sd(v)])
    return nice_top(mx * room)


def bar_panel(ax, runs, col, mp, noise, top, step, xlabel):
    """1 つの地図の棒グラフ（交通量 3 段階 × 4 方式，平均と標準偏差）."""
    bw = 0.2
    for k, (s, (lab, colr, _, _)) in enumerate(LINES.items()):
        xs, ys, es = [], [], []
        for t_i, (t, _) in enumerate(TRAFFIC):
            v = runs.get((mp, t, noise, s, col))
            if not v:
                continue
            m, sd = mean_sd(v)
            xs.append(t_i + (k - 1.5) * bw); ys.append(m); es.append(sd)
        ax.bar(xs, ys, bw, yerr=es, color=colr, edgecolor="k", lw=0.4, label=lab,
               error_kw=dict(lw=0.5, capsize=1.2, ecolor="k", capthick=0.5))
    ax.set_xlim(-0.55, 2.55)
    ax.set_xticks(range(3)); ax.set_xticklabels([t for _, t in TRAFFIC])
    ax.set_ylim(0, top); ax.set_yticks(range(0, top + 1, step))   # 凡例のために伸ばした所にも目盛りを振る
    ax.set_xlabel(xlabel, fontsize=FS_LABEL, linespacing=1.25)
    frame(ax, FS_TICK, grid="y")


def bar_legend(ax, warn=True):
    """棒グラフの凡例（上の余白に置き，棒・誤差棒にかぶらない所を選ぶ）."""
    return legend_free(ax, ("upper left", "upper right", "upper center"), FS_LEGEND - 0.4, warn=warn, ncol=2,
                       handlelength=0.9, handletextpad=0.3, columnspacing=0.5, borderpad=0.25)


def bar_legends(axs, top, step):
    """同じ指標のグラフすべてに凡例を入れる。どれかで棒にかぶるなら，縦軸の上端を 1 目盛りずつ伸ばして置き直す
    （伸ばした所にも目盛りを振る）."""
    for k in range(6):
        for ax in axs:
            ax.set_ylim(0, top); ax.set_yticks(range(0, top + 1, step))
            if ax.get_legend():
                ax.get_legend().remove()
        if all([bar_legend(ax, warn=(k == 5)) for ax in axs]):
            return
        top += step


def fig_bars(plt, runs, out, noise="gps5slow"):
    """論文の図: 制御メッセージ数（上）と判断余裕時間の確保率（下）を 1 枚に。全 6 条件，平均と標準偏差（乱数 5 回）.
    CDF の図と同じ大きさ（1 段の幅，2×2）にする。"""
    fig, axes = plt.subplots(2, 2, figsize=(W, H2))
    for i, (col, ylabel) in enumerate(BARS):
        top, step = bar_top(runs, col, noise, 1.55)
        for j, (mp, mtitle) in enumerate(MAPS):
            bar_panel(axes[i][j], runs, col, mp, noise, top, step, f"交通量\n({'abcd'[2 * i + j]}) {mtitle}")
            if j == 0:
                axes[i][j].set_ylabel(ylabel, fontsize=FS_LABEL, linespacing=1.15)
    fig.subplots_adjust(wspace=0.3, hspace=0.62)
    for i, (col, _) in enumerate(BARS):      # 凡例はすべてのグラフに入れる
        bar_legends(axes[i], *bar_top(runs, col, noise, 1.55))
    fig.savefig(os.path.join(out, "fig_bars.pdf"), bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


def fig_bar_one(plt, runs, out, col, ylabel, name, noise="gps5slow"):
    """論文の図: 1 つの指標の棒グラフ（2 つの地図を横に並べる）。各グラフの大きさは CDF の図の 1 つと同じ."""
    fig, axes = plt.subplots(1, 2, figsize=(W, H2 / 2 + 0.12))
    top, step = bar_top(runs, col, noise, 1.55)
    for j, (mp, mtitle) in enumerate(MAPS):
        bar_panel(axes[j], runs, col, mp, noise, top, step, f"交通量\n({'ab'[j]}) {mtitle}")
    axes[0].set_ylabel(ylabel, fontsize=FS_LABEL, linespacing=1.15)
    fig.subplots_adjust(wspace=0.3)
    bar_legends(axes, top, step)            # 凡例はすべてのグラフに入れる
    fig.savefig(os.path.join(out, name), bbox_inches="tight", pad_inches=0.02)
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
    legend_in(axes[0][0 if m == "conn_dur" else 1], "lower right", 5.8, handlelength=1.6)   # 曲線と重ならない右下に置く
    fig.subplots_adjust(wspace=0.25, hspace=0.55)
    fig.savefig(os.path.join(out, name), bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


def fig_gps(plt, hist, out):
    """報告用: 測位誤差の有無による接続の継続時間の比較（交通量多の 2 条件）."""
    conds = (("campus_high", "大学周辺・交通量多"), ("arterial_high", "幹線道路・交通量多"))
    sch = {PROPOSED: ("提案方式", LINES[PROPOSED][1], 1.2), "dist_r100": ("従来 100 m", LINES["dist_r100"][1], 0.9)}
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
    legend_in(axes[1], "lower right", 6.5)
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
    ap.add_argument("--runs", default="",
                    help="棒グラフに使う eval_report.py の all_runs.csv（既定 <cdf_hist.csv のフォルダ>/../report/all_runs.csv）")
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
    runs_csv = a.runs or os.path.join(os.path.dirname(os.path.abspath(a.hist)), "..", "report", "all_runs.csv")
    if os.path.exists(runs_csv):
        runs = read_runs(runs_csv)
        fig_bars(plt, runs, out)
        fig_bar_one(plt, runs, out, "ctrl_per_veh_min", BARS[0][1], "fig_ctrl.pdf")
        fig_bar_one(plt, runs, out, "met_margin_es", BARS[1][1], "fig_margin.pdf")
    else:
        print(f"（{runs_csv} が無いので棒グラフ fig_bars.pdf は描きません）")
    fig_all(plt, hist, out, "conn_dur", "rep_cdf_dur_all.pdf")
    fig_all(plt, hist, out, "peers", "rep_cdf_peers_all.pdf")
    fig_gps(plt, hist, out)
    print(f"図: {out}（フォント: {font}）")
    print("  fig_cdf.pdf / fig_bars.pdf / fig_ctrl.pdf / fig_margin.pdf / rep_cdf_dur_all.pdf / rep_cdf_peers_all.pdf / rep_cdf_gps.pdf")


if __name__ == "__main__":
    main()
