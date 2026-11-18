"""正式評価の結果まとめ（1つの HTML にグラフ・全条件の表・説明を入れる）を作る.

    python make_summary_html.py --runs <all_runs.csv> --hist <cdf_hist.csv> --out results/eval_summary.html

E17（到着予想時間は道のり，τ=20 s，測位誤差5 mの6条件は乱数10回，誤差なしは5回）の結果用。
グラフは研究室の決まり（eval_cdf_figs.py の先頭を参照）で描き，PNG を HTML に埋め込む。
文章（結果・注意・使い方）は E17 の数値を見て書いたもの。条件を変えて実行し直したときは，文章も見直すこと。
"""
import argparse
import base64
import io
import os
import statistics as st

import eval_cdf_figs as F

PROP, P15 = "eta_t20_j100_d100", "eta_t15_j100_d100"
CONV = ("dist_r100", "dist_r150", "dist_r200")
SCHEMES = (PROP,) + CONV
NOISES = (("gps0", "誤差なし"), ("gps5slow", "誤差5 m"))
MAPS = (("campus", "大学周辺"), ("arterial", "幹線道路を含む"))
TRAFFIC = (("low", "少"), ("mid", "中"), ("high", "多"))
PCT = {"met_margin_es", "noshare_pct", "short_pct", "enc_connected", "wasted_pct"}

METRICS = [
    ("m1", "ctrl_per_veh_min", "1. 制御メッセージ数", "主な指標", "1台あたりの制御\nメッセージ数 [回/分]", 1,
     "単位：回/分（車両1台あたり），少ないほど良い",
     "車両1台が1分間にサーバとやり取りするメッセージ数。本システムは経路取得時の問い合わせ，参加・離脱とその通知，"
     "参加中のグループごとに15秒に1回の生存通知。従来方式は2秒に1回の近隣端末の問い合わせと新しい相手の接続依頼。",
     "全12条件で本システムが最少。従来100 mに対して27〜50%。大学周辺・交通量中（誤差5 m）で 48.4 対 97.3，"
     "幹線道路を含む・交通量多（誤差5 m）で 36.7 対 113.3。",
     "本システムの値は誤差の有無でほとんど変わらない。従来方式は車が多く誤差があると増える（つなぎ直しが増えるため）。"
     "τ=20秒はτ=15秒より早く参加するぶん少し多い（大学周辺・中で 39.1 → 48.4）。",
     "そのまま使える。本システムの一番の利点。論文の図7(a)(b)。"),
    ("m2", "met_margin_es", "2. 判断余裕時間の確保率", "主な指標", "判断余裕時間の\n確保率 [%]", 1,
     "単位：%，高いほど良い",
     "エッジサーバのある交差点で出会った2台（同じ交差点を5秒以内に続けて通過した2台）のうち，"
     "必要な判断余裕時間 T<sub>m</sub> = 4.1秒 ＋ 速度÷2.0 m/s² より前に接続できていた割合。"
     "4.1秒と2.0 m/s² は国土交通省「通信利用型運転支援システムのガイドライン」（2011）の情報提供の値による。",
     "全12条件で本システムが最大。大学周辺で 89.3〜92.1%（従来100 mは 47.3〜60.8%），"
     "幹線道路を含む地図で 98.8〜99.5%（従来100 mは 73.0〜76.0%）。従来方式は半径を200 mに広げても届かない（最大 90.8%）。",
     "大学周辺では約90%で，100%に届かない約10%の要因は未確認。τ=15秒では大学周辺（誤差5 m）で 67.5〜77.9% だった（9. を参照）。",
     "そのまま使える。論文の図7(c)(d)。"),
    ("m3", "noshare_pct", "3. 進路が交わらない相手との接続", "主な指標", "進路が交わらない\n相手との接続 [%]", 1,
     "単位：%，低いほど良い",
     "接続のうち，経路が1か所も交わらず出会うことのない相手との接続が占める割合。",
     "本システムは全条件で 0%。従来方式は半径100 mで 1.2〜2.8%，200 mで 4.7〜9.8%。",
     "従来100 mとの差は数%と小さい。半径を広げるほど増える，という傾向として示すのがよい。",
     "使えるが，差が小さいので主張の中心にはしない。論文では表2と本文。"),
    ("m4", "peers_mean", "4. 同時接続数", "主な指標", "同時接続数\n[台]", 1,
     "単位：台，少ないほど端末の負担が軽い",
     "1台が同時に持っている接続相手の数の平均。",
     "全12条件で従来100 mより多く，従来200 mより少ない。大学周辺・中（誤差5 m）で 14.3（従来100 m 6.4，200 m 14.9），"
     "幹線道路を含む・多（誤差5 m）で 23.7（従来100 m 16.9，150 m 26.2）。",
     "早めに参加するぶん相手が増える。τ=20秒はτ=15秒より多い（大学周辺・中で 9.9 → 14.3）。大学周辺の「多」は渋滞で約63台になる。",
     "使える。判断余裕時間とのつり合いとして示す（200 mより少ないのに余裕時間は上回る）。"),
    ("m5", "reconn_A_per_veh_min", "5. 再接続（境目でのつなぎ直し）", "主な指標", "1台あたりの\n再接続 [回/分]", 2,
     "単位：回/分（車両1台あたり），少ないほど良い",
     "切れてから10秒以内，かつ距離が50 m未満しか変わらないうちに同じ相手と再接続した回数。",
     "本システムが従来100 mを下回るのは12条件のうち3条件（大学周辺・多の誤差なしと誤差あり，幹線道路を含む・多の誤差あり）。"
     "誤差5 mで車が多いと従来方式は大きく増える（大学周辺・多で 0.7 対 10.7，幹線道路を含む・多で 1.6 対 4.3）。"
     "本システムは誤差の有無でほぼ変わらない。",
     "誤差なしや車が少ない条件では従来方式の方が少ない（幹線道路を含む・少〜中では従来方式のどの半径よりも多い）。"
     "近接する交差点のグループ間を移動する際に，同じ相手との接続が一度切れて再び結ばれるためと考えられる。"
     "τ=20秒にすると全12条件でτ=15秒より減った。",
     "条件つきで使える。「誤差に強い」とは言えるが「常に少ない」とは言えない。論文では表2と本文。"),
    ("m6", "short_pct", "6. 10秒以下で切れた接続", "補助の指標", "10 s以下で切れた\n接続 [%]", 1,
     "単位：%，低いほど良い",
     "終わった接続のうち，続いた時間が10秒以下だったものの割合（実行ごとの割合の平均）。",
     "従来100 mよりは全12条件で低い（大学周辺・中（誤差5 m）で 18.6 対 42.6）。大学周辺では全6条件で従来200 mよりも低い。",
     "幹線道路を含む地図では，交通量多・誤差5 mを除き，従来150 m・200 mの方が低い。",
     "補助的に使う。論文では接続の継続時間の分布（10.）とあわせて本文で述べる。"),
    ("m7", "enc_connected", "7. すれ違い全体で接続（参考）", "参考", "すれ違い全体で\n接続 [%]", 1,
     "単位：%",
     "距離が初めて30 m未満になった2台（場所を問わないすれ違い）が，そのとき接続していた割合。",
     "従来方式はほぼ100%。本システムは大学周辺で 89.2〜91.3%，幹線道路を含む地図で 73.5〜78.9%。",
     "本システムはエッジサーバのある交差点だけを対象にするので，それ以外の場所でのすれ違いは接続しない。"
     "優劣ではなく狙いの違いを示す値。",
     "参考。エッジサーバの数・配置の議論で使う。"),
    ("m8", "wasted_pct", "8. 無駄な接続（参考）", "参考", "無駄な接続\n[%]", 1,
     "単位：%，低いほど良い",
     "接続のうち，経路が交わらない，または一度も50 m以内に近づかなかったものの割合。",
     "本システムは 42.0〜53.9%（τ=15秒では 38.4〜44.6%）。従来100 mは誤差なしで 19.8〜36.8%と本システムより低いが，"
     "誤差ありで車が多いと 41.7〜60.2%まで上がる。",
     "誤差なしでは従来100 mの方が良い。早めに参加して交差点の手前から接続するため，まだ50 m以内に近づいていない相手との接続が"
     "多く数えられる。定義（50 m）の決め方に根拠が弱い。",
     "参考にとどめる。論文の主張には使わない。"),
]


def png(fig):
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=220, bbox_inches="tight", pad_inches=0.03)
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def fig_metric(plt, runs, col, ylabel):
    """2×2: 行 = 誤差なし／誤差5 m，列 = 大学周辺／幹線道路を含む。研究室の決まりの棒グラフ."""
    fig, axes = plt.subplots(2, 2, figsize=(F.W * 1.25, F.H2 * 1.15))
    top, step = max(F.bar_top(runs, col, n, 1.55) for n, _ in NOISES)
    k = 0
    for i, (n, nlab) in enumerate(NOISES):
        for j, (mp, mlab) in enumerate(MAPS):
            F.bar_panel(axes[i][j], runs, col, mp, n, top, step, f"交通量\n({'abcd'[k]}) {mlab}・{nlab}")
            if j == 0:
                axes[i][j].set_ylabel(ylabel, fontsize=F.FS_LABEL, linespacing=1.15)
            k += 1
    fig.subplots_adjust(wspace=0.3, hspace=0.62)
    cap = 100 if col in PCT else None
    for i in range(2):
        F.bar_legends(axes[i], top, step, cap=cap)
    s = png(fig)
    plt.close(fig)
    return s


def fig_cdf_all(plt, hist, m, noise):
    """全6条件の CDF（2×3）。凡例はすべてのグラフに."""
    fig, axes = plt.subplots(2, 3, figsize=(6.3, 4.0))
    for k, (c, title) in enumerate(F.CONDS):
        ax = axes[k // 3][k % 3]
        for s, (lab, col, ls, lw) in F.LINES.items():
            x, y = F.cdf(hist[(f"{c}_{noise}", m, s)])
            ax.plot(*F.step_xy(x, y), color=col, ls=ls, lw=lw, label=lab)
        F.style(ax, m, f"{F.XLABEL[m]}\n({'abcdef'[k]}) {title}", 7.5, 7)
        if k % 3 == 0:
            ax.set_ylabel("累積確率", fontsize=7.5)
    fig.subplots_adjust(wspace=0.25, hspace=0.55)
    for ax in axes.flat:
        F.legend_free(ax, F.CDF_LOCS, 5.8, warn=False, handlelength=1.2, handletextpad=0.3, borderpad=0.2,
                      labelspacing=0.1)
    s = png(fig)
    plt.close(fig)
    return s


def ms(v, nd):
    return f"{st.mean(v):.{nd}f} ± {st.stdev(v):.{nd}f}"


def metric_table(runs, col, nd):
    h = ("<details open><summary>全12条件の数値</summary><table><tr><th>地図</th><th>交通量</th><th>測位誤差</th>"
         "<th>本システム</th><th>従来100m</th><th>従来150m</th><th>従来200m</th></tr>")
    for mp, mlab in MAPS:
        for t, tlab in TRAFFIC:
            for n, nlab in NOISES:
                h += f"<tr><td>{mlab}</td><td>{tlab}</td><td>{nlab}</td>"
                h += "".join(f"<td>{ms(runs[(mp, t, n, s, col)], nd)}</td>" for s in SCHEMES) + "</tr>"
    return h + "</table></details>"


def tau_table(runs):
    cols = (("met_margin_es", "判断余裕時間の確保率 [%]", 1), ("ctrl_per_veh_min", "制御メッセージ数 [回/分]", 1),
            ("peers_mean", "同時接続数 [台]", 1), ("reconn_A_per_veh_min", "再接続 [回/分]", 2),
            ("short_pct", "10秒以下で切れた接続 [%]", 1))
    h = "<table class='ptab'><tr><th>地図</th><th>交通量</th><th>方式</th>" + "".join(f"<th>{c[1]}</th>" for c in cols) + "</tr>"
    for mp, mlab in MAPS:
        for t, tlab in TRAFFIC:
            for s, slab in ((P15, "τ=15秒"), (PROP, "τ=20秒"), ("dist_r100", "従来100 m"), ("dist_r200", "従来200 m")):
                h += f"<tr><td>{mlab}</td><td>{tlab}</td><td>{slab}</td>"
                h += "".join(f"<td>{ms(runs[(mp, t, 'gps5slow', s, c)], nd)}</td>" for c, _, nd in cols) + "</tr>"
    return h + "</table>"


def cdf_table(hist, noise):
    h = ("<table><tr><th>地図</th><th>交通量</th><th>方式</th><th>接続時間 中央値 [秒]</th><th>接続時間 90% [秒]</th>"
         "<th>10秒以下 [%]</th><th>60秒以上 [%]</th><th>接続数 中央値 [台]</th><th>接続数 90% [台]</th>"
         "<th>相手0台の時間 [%]</th></tr>")
    names = dict([(PROP, "本システム"), ("dist_r100", "従来100 m"), ("dist_r150", "従来150 m"), ("dist_r200", "従来200 m")])
    for mp, mlab in MAPS:
        for t, tlab in TRAFFIC:
            for s in SCHEMES:
                d, p = hist[(f"{mp}_{t}_{noise}", "conn_dur", s)], hist[(f"{mp}_{t}_{noise}", "peers", s)]
                nd, npr = sum(d.values()), sum(p.values())
                row = (F.quantile(d, 0.5), F.quantile(d, 0.9),
                       100 * sum(v for k, v in d.items() if k <= 10) / nd, 100 * sum(v for k, v in d.items() if k >= 60) / nd,
                       F.quantile(p, 0.5), F.quantile(p, 0.9), 100 * p.get(0, 0) / npr)
                h += (f"<tr><td>{mlab}</td><td>{tlab}</td><td>{names[s]}</td><td>{row[0]:.0f}</td><td>{row[1]:.0f}</td>"
                      f"<td>{row[2]:.1f}</td><td>{row[3]:.1f}</td><td>{row[4]:.0f}</td><td>{row[5]:.0f}</td><td>{row[6]:.1f}</td></tr>")
    return h + "</table>"


CSS = """body{font-family:'Yu Gothic UI','Meiryo',sans-serif;max-width:1000px;margin:0 auto;padding:24px;line-height:1.75;color:#222;background:#fff}
h1{font-size:24px;border-bottom:3px solid #2a78c8;padding-bottom:8px}h2{font-size:20px;margin-top:48px;border-left:6px solid #2a78c8;padding-left:10px}
h3{font-size:16px;margin-top:20px}img{max-width:100%;display:block;margin:10px auto;border:1px solid #ddd}img.half{max-width:620px}
table{border-collapse:collapse;font-size:13px;margin:10px 0;width:100%}th,td{border:1px solid #ccc;padding:4px 8px;text-align:right}th{background:#eef3f9}td:nth-child(-n+3),th:nth-child(-n+3){text-align:left}
td:nth-child(4){background:#eaf2fb;font-weight:bold}
.tag{display:inline-block;font-size:12px;padding:1px 8px;border-radius:10px;background:#2a78c8;color:#fff;margin-left:8px;vertical-align:middle}
.tag.sub{background:#888}dl{margin:8px 0}dt{font-weight:bold;margin-top:8px}dd{margin-left:1em}
.use{background:#f3f8ee;border-left:4px solid #6a9a3a;padding:6px 12px;margin:8px 0}.box{background:#f6f6f6;padding:12px 16px;border-radius:6px}
details{margin:8px 0}.ptab td:nth-child(4){background:none;font-weight:normal}summary{cursor:pointer;color:#2a78c8}nav a{margin-right:14px}
.cond td:nth-child(2){text-align:left}"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--runs", required=True)
    ap.add_argument("--hist", required=True)
    ap.add_argument("--out", default=os.path.join(F.HERE, "results", "eval_summary.html"))
    a = ap.parse_args()

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    F.setup_font(plt, matplotlib)
    plt.rcParams.update({"font.size": 7.5, "axes.linewidth": 0.6})
    F.PROPOSED, F.LINES = PROP, F.make_lines(PROP)
    F.tick_cap = lambda col: 100 if col in PCT else None      # 割合（%）の目盛りは 100 まで
    F.EXTRA_BARS = F.EXTRA_BARS + (("enc_connected", ""), ("wasted_pct", ""))
    runs = F.read_runs(a.runs)
    hist = F.read_hist(a.hist)

    h = [f"<!doctype html><html lang='ja'><head><meta charset='utf-8'><title>正式評価の結果まとめ</title><style>{CSS}</style></head><body>"]
    h.append("<h1>正式評価の結果まとめ（本システムと従来G-LocONの比較）</h1>")
    h.append("<p>2026/11/19 更新　神作英祐（到着予想時間の距離を道のりに改め，τ=20秒，測位誤差5 mの6条件を乱数10回で再評価した結果。"
             "EXPERIMENTS.md の E17）　／　このファイル1つに，グラフ・全条件の表・説明を入れてある。</p>")
    h.append("""<div class='box'><b>結論</b><ul>
<li>サーバとの通信回数は，全12条件で本システムが最少（従来100 mの27〜50%）。</li>
<li>交差点で判断の余裕を持って接続できた割合は，全12条件で本システムが最大（89.3〜99.5%。従来200 mは最大90.8%）。</li>
<li>進路が交わらない相手との接続は，本システムは全条件で0%。</li>
<li>同時接続数は，全条件で従来100 mより多く，従来200 mより少ない。</li>
<li>再接続は，多くの条件で従来方式が少ない。本システムが従来100 mより少ないのは車が多い3条件だけ。</li>
<li>1回の接続は長続きする（大学周辺・中で中央値28秒，従来100 mは12秒）。一方，交差点から離れた区間では接続しない（相手0台の時間が長い）。</li></ul></div>""")
    h.append("""<h2>実験の条件</h2><table class='cond'><tr><th>項目</th><th>内容</th></tr>
<tr><td>方法</td><td>SUMOで1回走らせ，その同じ走行の上で各方式（本システム，従来100 m・150 m・200 m）の接続を計算。無線や通信遅延は含まない</td></tr>
<tr><td>本システム</td><td>τ=20秒，ρ=δ=100 m，ε=20 m。経路に沿った交差点までの距離（道のり）÷速度が20秒未満，または直線距離が100 m未満で参加。交差点を通過し，100 m以上離れて遠ざかったら離脱。生存通知15秒ごと</td></tr>
<tr><td>従来G-LocON</td><td>自分から半径100・150・200 m以内の端末と接続，2秒ごとにサーバへ問い合わせ</td></tr>
<tr><td>地図</td><td>大学周辺（1.8×1.15 km，生活道路が中心，エッジサーバ20）／幹線道路を含む（3.0×2.7 km，国道16号などを含む，エッジサーバ40）</td></tr>
<tr><td>交通量</td><td>少・中・多。同時に走る台数は，大学周辺で約43・102・415台，幹線道路を含む地図で約182・325・585台</td></tr>
<tr><td>測位誤差</td><td>誤差なし／標準偏差5 m・約10秒かけてゆっくり変わる誤差（5 mは標準的なカーナビゲーションの測位誤差の目安）。接続の判断には誤差つきの位置，指標の計算には実際の位置を使う</td></tr>
<tr><td>繰り返し</td><td>誤差5 mの6条件 × 乱数10通り ＋ 誤差なしの6条件 × 乱数5通り = 90実行。車両は15分間発生</td></tr></table>""")
    h.append("""<h2>グラフと表の見方</h2><ul>
<li>指標のグラフは1枚に4つの枠がある。左が大学周辺，右が幹線道路を含む地図。上が誤差なし，下が誤差5 m。</li>
<li>青が本システム（τ=20秒），橙・緑・赤が従来方式（100 m・150 m・200 m）。</li>
<li>棒の高さは平均（誤差5 mは10回，誤差なしは5回），縦線は標準偏差（乱数を変えたときのばらつき）。</li>
<li>表の値は「平均 ± 標準偏差」。青い列が本システム。</li>
<li>大学周辺の「多」は渋滞が起きる条件なので，参考として見る。</li></ul>""")
    nav = "".join(f"<a href='#{mid}'>{title}</a>" for mid, _, title, *_ in METRICS)
    h.append(f"<nav><b>指標：</b>{nav}<a href='#p1'>9. τの比較</a><a href='#c1'>10. 接続時間・接続数の分布（CDF）</a></nav>")

    for mid, col, title, tag, ylabel, nd, unit, what, res, note, use in METRICS:
        cls = "tag" if tag == "主な指標" else "tag sub"
        h.append(f"<h2 id='{mid}'>{title}<span class='{cls}'>{tag}</span></h2><p>{unit}</p>"
                 f"<dl><dt>何を数えたか</dt><dd>{what}</dd><dt>結果</dt><dd>{res}</dd><dt>注意</dt><dd>{note}</dd></dl>"
                 f"<div class='use'><b>使い方：</b>{use}</div>")
        h.append(f"<img class='half' src='{fig_metric(plt, runs, col, ylabel)}'>")
        h.append(metric_table(runs, col, nd))
        print("done", title)

    h.append("""<h2 id='p1'>9. τの比較（15秒と20秒）</h2><div class='box'><b>結論</b><ul>
<li>到着予想時間を道のりで求めると，同じτでは直線距離のときより参加が遅くなる。τ=15秒では大学周辺の判断余裕時間の確保率が 67.5〜77.9% にとどまり，交通量多では従来200 m（76.5%）とほぼ同じだった。</li>
<li>τ=20秒にすると，全6条件で確保率が約90%以上（89.3〜99.5%）になる。制御メッセージ数は従来100 mの半分以下のまま，同時接続数は全条件で従来200 mより少ない。</li>
<li>τ=20秒では，再接続と10秒以下で切れた接続の割合も全条件でτ=15秒より減った。</li>
<li>そのため，論文と動作検証の設定を τ=20秒 とした。</li></ul></div>
<p>条件：誤差5 mの6条件，乱数10回（主の比較と同じ走行）。値は「平均 ± 標準偏差」。</p>""")
    h.append(tau_table(runs))
    h.append("<p><b>参考（直線距離で到着予想時間を求めていたときの比較，2026/10/20，E15）</b>：大学周辺・交通量中・誤差5 mで，"
             "ρ（参加円）は 0〜150 m で確保率がほとんど変わらず（78.6〜79.3%），δ（離脱円）を 30 m に小さくすると再接続が 2.50 回/分に増え確保率も 72.9% に下がった。"
             "道のりにしてからの ρ・δ の比較はまだ行っていない。</p>")

    h.append("""<h2 id='c1'>10. 接続時間と接続数の分布（CDF）</h2><div class='box'><b>結論</b><ul>
<li>本システムの接続は長続きする。大学周辺・交通量中（誤差5 m）で，1回の接続が続いた時間の中央値は 28秒（従来100 mは12秒，200 mは20秒）で，60秒以上続いた接続も 19.7%（従来100 mは5.8%）。幹線道路を含む地図・交通量多では中央値 40秒（従来200 mは34秒）。</li>
<li>本システムは「つながる時はまとめて，つながらない時は0台」。相手が0台の時間が大学周辺・中で 27.8%，幹線道路を含む・多で 37.3%（従来100 mは14.7%，7.8%）。エッジサーバの交差点に近づいた時だけグループに入り，交差点から離れた区間ではつながらないため。</li>
<li>交差点の近くでは同じグループの全員とつながるので，接続数の多い側（90%の値）は大学周辺・中で 37台と，従来200 m（36台）と同程度。</li>
<li>測位誤差の有無で本システムの分布はほとんど変わらない。従来方式は誤差ありで短い接続が増える（幹線道路を含む・多の従来200 mで10秒以下が 9.6% → 18.3%）。</li>
<li>幹線道路を含む地図の交通量少・中では，10秒以下で切れた接続が従来150 m・200 mより多い。</li></ul></div>
<h3>CDF（累積分布）の見方</h3><p>横軸の値「以下」になる割合を縦軸に描いたグラフ。例えば接続時間のグラフで（20秒, 0.5）を通る線は「接続の半分は20秒以下で切れた」という意味。線が右にあるほど値が大きい（接続時間なら長続きする，接続数なら多い）。接続時間の横軸は対数（10<sup>1</sup>=10秒，10<sup>2</sup>=100秒）。左端の縦軸の値は「0台」の割合。全実行のデータをまとめて1本の線にしている。接続数は1秒ごと・1台ごとの値。</p>""")
    for n, nlab in NOISES:
        h.append(f"<h3>接続1回が続いた時間（{nlab}）</h3><img src='{fig_cdf_all(plt, hist, 'conn_dur', n)}'>")
        h.append(f"<h3>同時接続数（{nlab}）</h3><img src='{fig_cdf_all(plt, hist, 'peers', n)}'>")
    h.append("<details open><summary>分布の数値（誤差5 m，10回分をまとめた値）</summary>" + cdf_table(hist, "gps5slow") + "</details>")
    h.append("<details><summary>分布の数値（誤差なし，5回分をまとめた値）</summary>" + cdf_table(hist, "gps0") + "</details>")
    h.append("<p>注意：4. の同時接続数は実行ごとの平均をさらに平均した値，この節の分布は全実行のデータをまとめた値なので，わずかに違う（例：14.3 と 14.2）。"
             "6. の10秒以下の割合も同じ理由で，この節の値とわずかに違う（例：18.6 と 18.5）。</p>")
    h.append("""<h2>元データの場所</h2><ul>
<li>90実行の生データ（GitHub には上げない）：D:\\Research\\Program\\G-LocON\\G-LocON_2026\\G-LocON_M2M_Kansaku\\G-LocON_V2V_Server\\SimBridge\\results\\eval_detail_path\\main\\raw\\（列の説明は各フォルダの README_detail.txt）</li>
<li>全実行の値・集計・CDF の度数分布：D:\\Research\\eval_path_copy10\\，GitHub の G-LocON_V2V_Server/SimBridge/results/20261118_eval_main_path10/</li>
<li>このファイルの作り方：<code>python make_summary_html.py --runs all_runs.csv --hist cdf_hist.csv</code></li></ul></body></html>""")
    with open(a.out, "w", encoding="utf-8") as f:
        f.write("\n".join(h))
    print("wrote", a.out)


if __name__ == "__main__":
    main()
