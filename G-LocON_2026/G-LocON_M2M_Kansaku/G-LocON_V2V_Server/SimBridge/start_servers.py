"""SUMO実験用のサーバ一式（MasterServer＋全エッジサーバ）をまとめて起動する.

    python start_servers.py            # scenario/edge_servers.csv の全交差点分を起動
    python start_servers.py --stun     # STUNServer も起動（実機を使う場合）

IntelliJ でプロジェクトをビルド済み（../out/production/ にクラスがある）であること。
Ctrl+C で全プロセスを終了する。ログは out/servers/<サーバ名>.log に保存する。

画面には各エッジサーバの JOIN / LEAVE（とエラー），スマホの STUN への最初の接続（Hello）を時刻付きで表示する
（KEEPALIVE による JOIN(UPDATE)，STUN の20秒ごとの Ping は出さない）。
    python start_servers.py --show all    # 全ての出力を表示
    python start_servers.py --show none   # 画面には何も出さない（ログファイルのみ）
"""
import argparse
import os
import signal
import subprocess
import sys
import threading
import time

import common

SERVER_DIR = os.path.normpath(os.path.join(common.HERE, ".."))


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
                or line.startswith("STUNServer;getMsg:Hello")      # スマホが「開始」を押した（STUNへの最初の接続）
                or "エラー" in line or "Exception" in line)

    def pump(name, p, log):
        # サーバの出力を1行ずつログファイルに書き，必要なものは画面にも出す
        for raw in p.stdout:
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            log.write(time.strftime("%H:%M:%S ") + line + "\n")
            log.flush()
            if wanted(line):
                with print_lock:
                    print(f"{time.strftime('%H:%M:%S')} [{name}] {line}", flush=True)

    def launch(name, args):
        log = open(os.path.join(logdir, name + ".log"), "w", encoding="utf-8")
        p = subprocess.Popen(["java", "-Dfile.encoding=UTF-8"] + args,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        t = threading.Thread(target=pump, args=(name, p, log), daemon=True)
        t.start()
        procs.append((name, p, log, t))

    if a.stun:
        launch("STUNServer", ["-cp", cp("STUNServer"), "stun_server.StartUp"])
    launch("MasterServer", ["-cp", cp("MasterServer"), "master_server.StartUp", a.csv])
    n = 0
    with open(a.csv, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or line.startswith("intersectionId") or not line.strip():
                continue
            iid, _ip, port = line.strip().split(",")[:3]
            launch(f"EdgeServer_{port}", ["-cp", cp("EdgeServer"), "edge_server.StartUp", iid, port])
            n += 1
    time.sleep(2)
    dead = [name for name, p, _, _ in procs if p.poll() is not None]
    print(f"起動: MasterServer 1, EdgeServer {n}{', STUNServer 1' if a.stun else ''}（ログ: {logdir}）")
    if dead:
        print("起動に失敗したもの: " + ", ".join(dead) + "（ログを確認してください）")
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
