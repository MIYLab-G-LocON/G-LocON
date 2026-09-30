@echo off
chcp 65001 >nul
rem 固定ルート用（評価1: ES1〜ES3）のサーバ一式を起動する
rem ダブルクリックで起動できる（このファイルのあるフォルダに移動してから実行する）。
rem 引数を足したいときは，このファイルをコピーして最後の行を書き換える。
cd /d "%~dp0"
title run_servers_fixed_route.bat
python start_servers.py --stun --csv ..\MasterServer\edge_servers.csv %*
pause
