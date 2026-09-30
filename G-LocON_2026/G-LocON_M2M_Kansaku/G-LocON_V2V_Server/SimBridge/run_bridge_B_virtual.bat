@echo off
chcp 65001 >nul
rem 評価3: SUMO＋仮想クライアント＋実機1台（モードB）
rem ダブルクリックで起動できる（このファイルのあるフォルダに移動してから実行する）。
rem 引数を足したいときは，このファイルをコピーして最後の行を書き換える。
cd /d "%~dp0"
title run_bridge_B_virtual.bat
python sim_bridge.py --phones 1 --virtual --gui --follow-phone %*
pause
