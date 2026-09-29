"""SUMO実験用のサーバ一式（MasterServer＋全エッジサーバ）をまとめて起動する.

    python start_servers.py            # scenario/edge_servers.csv の全交差点分を起動
    python start_servers.py --stun     # STUNServer も起動（実機を使う場合）

IntelliJ でプロジェクトをビルド済み（../out/production/ にクラスがある）であること。
Ctrl+C で全プロセスを終了する。ログは out/servers/ に保存する。
"""
import argparse
import os
import subprocess
import sys
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
    a = ap.parse_args()

    logdir = os.path.join(common.OUT_DIR, "servers")
    os.makedirs(logdir, exist_ok=True)

    def cp(module):
        return os.pathsep.join([os.path.join(a.classes, module), a.json_jar])

    procs = []

    def launch(name, args):
        log = open(os.path.join(logdir, name + ".log"), "w", encoding="utf-8")
        p = subprocess.Popen(["java", "-Dfile.encoding=UTF-8"] + args, stdout=log, stderr=subprocess.STDOUT)
        procs.append((name, p, log))

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
    dead = [name for name, p, _ in procs if p.poll() is not None]
    print(f"起動: MasterServer 1, EdgeServer {n}{', STUNServer 1' if a.stun else ''}（ログ: {logdir}）")
    if dead:
        print("起動に失敗したもの: " + ", ".join(dead) + "（ログを確認してください）")
    print("Ctrl+C で全て終了します")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        for name, p, log in procs:
            p.terminate()
        for name, p, log in procs:
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()
            log.close()
        print("全サーバを終了しました")


if __name__ == "__main__":
    main()
