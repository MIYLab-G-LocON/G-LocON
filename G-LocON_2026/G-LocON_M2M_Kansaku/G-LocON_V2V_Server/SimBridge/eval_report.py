"""正式評価の集計: eval_run.py の結果から，表（CSV・Excel・Markdown）とグラフ（PNG）を作る.

    python eval_report.py --plan main      # results/eval/main/*.csv を集計
    python eval_report.py --plan params
    python eval_report.py --plan quick

作るもの（results/eval/<plan>/report/）:
    all_runs.csv        全実行・全方式の値をそのまま並べたもの（1行 = 1実行の1方式）
    summary.csv         条件（地図・交通量・測位誤差・方式）ごとの平均・標準偏差・実行数（Excel で開ける）
    summary.xlsx        同じ内容を，指標ごとのシートに「平均 ± 標準偏差」で並べたもの（openpyxl があるとき）
    summary.md          同じ内容の Markdown の表（README・報告資料に貼る用）
    fig_<指標>.png      指標ごとのグラフ（棒 = 平均，線 = 標準偏差）

指標の定義は README 7.5。ここで使う列は compare_schemes.py の出力（schemes.csv）のもの。
"""
import argparse
import csv
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RESULT_ROOT = os.path.join(HERE, "results", "eval")

# 正式評価の指標: (列名, 表示名, 単位, 良い方向)
METRICS = [
    ("ctrl_per_veh_min",     "制御メッセージ数",                       "回/台・分", "少ないほど良い"),
    ("peers_mean",           "同時につながる相手の数",                 "台",        "少ないほど良い"),
    ("met_margin_es",        "判断の余裕を持って接続（ES交差点）",     "%",         "高いほど良い"),
    ("noshare_pct",          "ルートが交わらない相手との接続",         "%",         "低いほど良い"),
    ("reconn_A_per_veh_min", "境目でのつなぎ直し",                     "回/台・分", "少ないほど良い"),
    ("short_pct",            "10秒以下で切れた接続",                   "%",         "低いほど良い"),
    ("enc_connected",        "すれ違い全体で接続（参考）",             "%",         "狙いの違い"),
    ("wasted_pct",           "無駄な接続（参考）",                     "%",         "低いほど良い"),
]
MAP_JA = {"campus": "大学周辺", "arterial": "幹線道路を含む"}
TRAFFIC_JA = {"low": "少", "mid": "中", "high": "多"}
NOISE_JA = {"gps0": "誤差なし", "gps5slow": "誤差5m（ゆっくり）", "gps5rand": "誤差5m（ばらばら）",
            "gps3slow": "誤差3m（ゆっくり）", "gps3rand": "誤差3m（ばらばら）"}
ORDER = {k: i for i, k in enumerate(["campus", "arterial", "low", "mid", "high",
                                     "gps0", "gps3slow", "gps3rand", "gps5slow", "gps5rand"])}


def scheme_label(name):
    m = re.fullmatch(r"eta_t(\d+)_j(\d+)_d(\d+)", name)
    if m:
        t, j, d = m.groups()
        return "本システム" if (t, j, d) == ("15", "100", "100") else f"本システム τ{t} ρ{j} δ{d}"
    m = re.fullmatch(r"dist_r(\d+)", name)
    return f"従来{m.group(1)}m" if m else name


def scheme_key(name):
    m = re.fullmatch(r"eta_t(\d+)_j(\d+)_d(\d+)", name)
    if m:
        return (0,) + tuple(int(x) for x in m.groups())
    m = re.fullmatch(r"dist_r(\d+)", name)
    return (1, int(m.group(1)), 0, 0) if m else (2, 0, 0, 0)


def fnum(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def mean_sd(xs):
    xs = [x for x in xs if x is not None]
    if not xs:
        return None, None, 0
    m = sum(xs) / len(xs)
    sd = math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1)) if len(xs) > 1 else 0.0
    return m, sd, len(xs)


def load(plan):
    d = os.path.join(RESULT_ROOT, plan)
    if not os.path.isdir(d):
        sys.exit(f"結果がありません: {d}（先に python eval_run.py --plan {plan}）")
    rows = []
    for fn in sorted(os.listdir(d)):
        if fn.endswith(".csv"):
            with open(os.path.join(d, fn), encoding="utf-8") as f:
                rows += list(csv.DictReader(f))
    if not rows:
        sys.exit(f"結果がありません: {d}（先に python eval_run.py --plan {plan}）")
    return rows


def summarize(rows):
    """{(map, traffic, noise, scheme): {metric: (mean, sd, n)}}"""
    groups = {}
    for r in rows:
        groups.setdefault((r["map"], r["traffic"], r["noise"], r["scheme"]), []).append(r)
    out = {}
    for k, rs in groups.items():
        out[k] = {m[0]: mean_sd([fnum(r.get(m[0])) for r in rs]) for m in METRICS}
        out[k]["_concurrent"] = mean_sd([fnum(r.get("concurrent")) for r in rs])
    return out


def cond_key(k):
    return (ORDER.get(k[0], 99), ORDER.get(k[1], 99), ORDER.get(k[2], 99), scheme_key(k[3]))


def fmt(ms, digits=1):
    m, sd, n = ms
    if m is None:
        return ""
    return f"{m:.{digits}f} ± {sd:.{digits}f}" if n > 1 else f"{m:.{digits}f}"


def write_csv(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8-sig") as f:     # utf-8-sig: Excel で文字化けしない
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


def write_tables(out, rows, summ):
    # 全実行
    keys = ["plan", "run", "map", "traffic", "noise", "seed", "scheme", "concurrent"] + [m[0] for m in METRICS]
    write_csv(os.path.join(out, "all_runs.csv"), keys, [[r.get(k, "") for k in keys] for r in rows])

    # 条件ごとの平均・標準偏差
    head = ["地図", "交通量", "同時台数", "測位誤差", "方式", "実行数"]
    for _, name, unit, _ in METRICS:
        head += [f"{name} [{unit}] 平均", "標準偏差"]
    body = []
    for k in sorted(summ, key=cond_key):
        s = summ[k]
        n = max(v[2] for v in s.values())
        line = [MAP_JA.get(k[0], k[0]), TRAFFIC_JA.get(k[1], k[1]), f"{s['_concurrent'][0]:.0f}" if s["_concurrent"][0] else "",
                NOISE_JA.get(k[2], k[2]), scheme_label(k[3]), n]
        for col, *_ in METRICS:
            m, sd, _n = s[col]
            line += ["" if m is None else round(m, 2), "" if m is None else round(sd, 2)]
        body.append(line)
    write_csv(os.path.join(out, "summary.csv"), head, body)

    # 指標ごとの表（行 = 条件，列 = 方式）: Markdown と Excel
    schemes = sorted({k[3] for k in summ}, key=scheme_key)
    conds = sorted({k[:3] for k in summ}, key=lambda c: cond_key(c + (schemes[0],))[:3])
    md = ["# 正式評価の集計\n", f"実行数: {len({r['run'] for r in rows})}。値は「平均 ± 標準偏差」（乱数を変えた繰り返し）。\n"]
    sheets = []
    for col, name, unit, good in METRICS:
        title = f"{name} [{unit}]（{good}）"
        h = ["地図", "交通量", "測位誤差"] + [scheme_label(s) for s in schemes]
        t = [[MAP_JA.get(c[0], c[0]), TRAFFIC_JA.get(c[1], c[1]), NOISE_JA.get(c[2], c[2])]
             + [fmt(summ[c + (s,)][col]) if c + (s,) in summ else "" for s in schemes] for c in conds]
        md += [f"## {title}\n", "| " + " | ".join(h) + " |", "|" + "---|" * len(h)]
        md += ["| " + " | ".join(str(x) for x in line) + " |" for line in t]
        md.append("")
        sheets.append((name[:28], title, h, t))
    with open(os.path.join(out, "summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    try:
        import openpyxl
    except ImportError:
        print("（openpyxl が無いので summary.xlsx は作りません。pip install openpyxl で作れます）")
        return
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "一覧"
    ws.append(head)
    for line in body:
        ws.append(line)
    for sname, title, h, t in sheets:
        ws = wb.create_sheet(re.sub(r"[\\/*?:\[\]]", "_", sname))
        ws.append([title])
        ws.append(h)
        for line in t:
            ws.append(line)
    wb.save(os.path.join(out, "summary.xlsx"))


def setup_font(plt):
    """日本語の出るフォントを選ぶ（Windows: Yu Gothic / Meiryo，Linux: Noto Sans CJK）."""
    from matplotlib import font_manager
    have = {f.name for f in font_manager.fontManager.ttflist}
    for name in ("Yu Gothic", "Meiryo", "MS Gothic", "Hiragino Sans", "Noto Sans CJK JP", "IPAexGothic", "TakaoGothic"):
        if name in have:
            plt.rcParams["font.family"] = name
            return True
    return False


# 方式の色（本システム = 青，従来 = 灰色の濃淡）。色だけに頼らないよう，棒の上に値も書く
COLORS = ["#2a78c8", "#b9bcc2", "#8d9198", "#5f646c", "#7fb2e5", "#f0a23b", "#57a773", "#c8553d", "#8e6bbf", "#4c4f55"]


def plot_metric(plt, out, summ, col, name, unit, good, ja):
    schemes = sorted({k[3] for k in summ}, key=scheme_key)
    maps = sorted({k[0] for k in summ}, key=lambda x: ORDER.get(x, 99))
    noises = sorted({k[2] for k in summ}, key=lambda x: ORDER.get(x, 99))
    traffics = sorted({k[1] for k in summ}, key=lambda x: ORDER.get(x, 99))
    fig, axes = plt.subplots(len(noises), len(maps), figsize=(5.2 * len(maps), 3.3 * len(noises)), squeeze=False)
    w = 0.8 / len(schemes)
    for i, nz in enumerate(noises):
        for j, mp in enumerate(maps):
            ax = axes[i][j]
            for si, sc in enumerate(schemes):
                xs, ys, es = [], [], []
                for ti, tr in enumerate(traffics):
                    v = summ.get((mp, tr, nz, sc), {}).get(col)
                    if v and v[0] is not None:
                        xs.append(ti + (si - (len(schemes) - 1) / 2) * w)
                        ys.append(v[0])
                        es.append(v[1])
                lab = scheme_label(sc) if ja else sc
                ax.bar(xs, ys, w * 0.92, yerr=es, capsize=2, color=COLORS[si % len(COLORS)], label=lab,
                       error_kw={"elinewidth": 0.8, "ecolor": "#333333"})
                if len(schemes) <= 4:
                    for x, y, e in zip(xs, ys, es):       # 値は誤差の線の上に書く（線と重ならないように）
                        ax.annotate(f"{y:.1f}" if y < 100 else f"{y:.0f}", (x, y + e), xytext=(0, 2),
                                    textcoords="offset points", ha="center", va="bottom", fontsize=7)
            ax.set_xticks(range(len(traffics)))
            if ja:
                ax.set_xticklabels([f"交通量 {TRAFFIC_JA.get(t, t)}" for t in traffics])
                ax.set_title(f"{MAP_JA.get(mp, mp)}，{NOISE_JA.get(nz, nz)}", fontsize=10)
                ax.set_ylabel(unit)
            else:
                ax.set_xticklabels([f"traffic {t}" for t in traffics])
                ax.set_title(f"{mp}, {nz}", fontsize=10)
            ax.set_ylim(bottom=0)
            ax.margins(y=0.12)
            ax.grid(axis="y", linewidth=0.4, alpha=0.5)
            ax.set_axisbelow(True)
            for sp in ("top", "right"):
                ax.spines[sp].set_visible(False)
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.suptitle(f"{name}（{good}）" if ja else col, fontsize=12)
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    fig.legend(handles, labels, loc="lower center", ncol=min(len(labels), 5), fontsize=9, frameon=False)   # 凡例は図の下（棒と重ならないように）
    fig.savefig(os.path.join(out, f"fig_{col}.png"), dpi=160)
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--plan", default="quick", help="quick / main / params / noise")
    ap.add_argument("--no-fig", action="store_true", help="グラフを作らない")
    a = ap.parse_args()

    rows = load(a.plan)
    summ = summarize(rows)
    out = os.path.join(RESULT_ROOT, a.plan, "report")
    os.makedirs(out, exist_ok=True)
    write_tables(out, rows, summ)

    # 画面にも主な表を出す
    schemes = sorted({k[3] for k in summ}, key=scheme_key)
    n_runs = len({r["run"] for r in rows})
    print(f"計画 {a.plan}: {n_runs} 実行を集計（平均 ± 標準偏差）\n")
    for col, name, unit, good in METRICS[:6]:
        print(f"■ {name} [{unit}]（{good}）")
        for c in sorted({k[:3] for k in summ}, key=lambda c: cond_key(c + (schemes[0],))[:3]):
            cells = "  ".join(f"{scheme_label(s)}: {fmt(summ[c + (s,)][col])}" for s in schemes if c + (s,) in summ)
            print(f"  {MAP_JA.get(c[0], c[0])}・交通量{TRAFFIC_JA.get(c[1], c[1])}・{NOISE_JA.get(c[2], c[2])}  →  {cells}")
        print()

    if not a.no_fig:
        try:
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
        except ImportError:
            print("（matplotlib が無いのでグラフは作りません。pip install matplotlib で作れます）")
        else:
            ja = setup_font(plt)
            if not ja:
                print("（日本語フォントが見つからないので，グラフの文字は英語にします）")
            for col, name, unit, good in METRICS:
                plot_metric(plt, out, summ, col, name, unit, good, ja)
    print(f"表・グラフ → {out}")


if __name__ == "__main__":
    main()
