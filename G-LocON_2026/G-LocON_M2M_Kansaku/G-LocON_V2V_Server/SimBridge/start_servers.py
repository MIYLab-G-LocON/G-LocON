"""SUMO実験用のサーバ一式（MasterServer＋全エッジサーバ）をまとめて起動する.

    python start_servers.py            # scenario/edge_servers.csv の全交差点分を起動
    python start_servers.py --stun     # STUNServer も起動（実機を使う場合）

IntelliJ でプロジェクトをビルド済み（../out/production/ にクラスがある）であること。
Ctrl+C で全プロセスを終了する。ログは out/servers/<サーバ名>.log に保存する。

画面には各エッジサーバの JOIN / LEAVE（とエラー），スマホが STUN につないだこと（「開始」を押したとき）を時刻付きで表示する
（KEEPALIVE による JOIN(UPDATE)，STUN の20秒ごとの Ping は出さない）。

起動前に使うポートが空いているかを調べ，前に起動したサーバ（java）が残っていれば起動せずに知らせる
（残ったサーバがポートを握ったままだと，新しいサーバが起動に失敗し，スマホがつながらなくなる）。
    python start_servers.py --show all    # 全ての出力を表示
    python start_servers.py --show none   # 画面には何も出さない（ログファイルのみ）
"""
import argparse
import os
import signal
import socket
import subprocess
import sys
import threading
import time

import common

SERVER_DIR = os.path.normpath(os.path.join(common.HERE, ".."))


def port_in_use(port):
    """UDPポートが既に使われていれば True（前に起動したサーバが残っている）."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind(("0.0.0.0", port))
        return False
    except OSError:
        return True
    finally:
        s.close()


KILL_HINT = ("前に起動したサーバ（java）が残っている可能性があります。\n"
             "  1. 前の start_servers.py のウィンドウがあれば Ctrl+C で止める\n"
             "  2. PowerShell で  Get-Process java | Stop-Process -Force  を実行する\n"
             "  3. もう一度 start_servers.py を起動する（README 9.9）")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes", default=os.path.join(SERVER_DIR, "out", "production"),
                    help="コンパイル済みクラスのディレクトリ（IntelliJ の out/production）")
    ap.add_argument("--json-jar", default=os.path.join(SERVER_DIR, "SignalingServer", "lib", "java-json.jar"))
    ap.add_argument("--csv", default=common.SIM_EDGE_SERVERS_CSV, help="エッジサーバ一覧")
    ap.add_argument("--stun", action="store_true", help="STUNServer も起動する")
    ap.add_argument("--show", choices=["group", "all", "none"], default="group",
                    help="画面に出す内容: group=JOIN/LEAVEとエラー（既定），all=全て，none=なし")
    a = ap.parse_args()

    logdir = os.path.join(common.OUT_DIR, "servers")
    os.makedirs(logdir, exist_ok=True)

    def cp(module):
        return os.pathsep.join([os.path.join(a.classes, module), a.json_jar])

    procs = []
    print_lock = threading.Lock()

    def wanted(line):
        if a.show == "all":
            return True
        if a.show == "none":
            return False
        return (line.startswith("JOIN:") or line.startswith("LEAVE:")
                or line.startswith("IP>>")                         # スマホが STUN につないだ（「開始」を押した）
                or "エラー" in line or "Exception" in line or "already in use" in line)

    def pump(name, p, log):
        # サーバの出力を1行ずつログファイルに書き，必要なものは画面にも出す
        for raw in p.stdout:
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            log.write(time.strftime("%H:%M:%S ") + line + "\n")
            log.flush()
            if wanted(line):
                if line.startswith("IP>>"):
                    line = "スマホが接続（STUN）: " + line
                with print_lock:
                    print(f"{time.strftime('%H:%M:%S')} [{name}] {line}", flush=True)

    def launch(name, args):
        log = open(os.path.join(logdir, name + ".log"), "w", encoding="utf-8")
        p = subprocess.Popen(["java", "-Dfile.encoding=UTF-8"] + args,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        t = threading.Thread(target=pump, args=(name, p, log), daemon=True)
        t.start()
        procs.append((name, p, log, t))

    edges = []
    with open(a.csv, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or line.startswith("intersectionId") or not line.strip():
                continue
            iid, _ip, port = line.strip().split(",")[:3]
            edges.append((iid, port))

    # 起動前に，使うポートが空いているか調べる
    ports = ([55554] if a.stun else []) + [55556] + [int(p) for _, p in edges]
    busy = [p for p in ports if port_in_use(p)]
    if busy:
        print("!" * 70)
        print("起動できません: 次のポートが既に使われています: " + ", ".join(map(str, busy)))
        print(KILL_HINT)
        print("!" * 70)
        sys.exit(1)

    if a.stun:
        launch("STUNServer", ["-cp", cp("STUNServer"), "stun_server.StartUp"])
    launch("MasterServer", ["-cp", cp("MasterServer"), "master_server.StartUp", a.csv])
    for iid, port in edges:
        launch(f"EdgeServer_{port}", ["-cp", cp("EdgeServer"), "edge_server.StartUp", iid, port])
    time.sleep(2)
    # 起動に失敗したサーバ（プロセスが終わった，またはポートを使えていない）
    dead = [name for name, p, _, _ in procs if p.poll() is not None]
    if a.stun and "STUNServer" not in dead and not port_in_use(55554):
        dead.append("STUNServer")          # STUN はポートを取れないとメッセージだけ出して終わる
    print(f"起動: MasterServer 1, EdgeServer {len(edges)}{', STUNServer 1' if a.stun else ''}（ログ: {logdir}）")
    if dead:
        print("!" * 70)
        print("起動に失敗したもの: " + ", ".join(dead) + "（ログ: out/servers/<名前>.log）")
        print("このままではスマホがつながりません。Ctrl+C で止めて，原因を直してから起動し直してください。")
        print(KILL_HINT)
        print("!" * 70)
    print("Ctrl+C で全て終了します")

    def on_term(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, on_term)   # 終了要求（kill など）でも子プロセスを残さない
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        for name, p, log, t in procs:
            p.terminate()
        for name, p, log, t in procs:
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()
            t.join(timeout=2)
            log.close()
        print("全サーバを終了しました")


if __name__ == "__main__":
    main()
