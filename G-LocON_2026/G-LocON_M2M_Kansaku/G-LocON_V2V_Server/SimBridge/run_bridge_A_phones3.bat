@echo off
chcp 65001 >nul
rem 評価2: SUMO＋実機3台（モードA）
rem ダブルクリックで起動できる（このファイルのあるフォルダに移動してから実行する）。
rem 引数を足したいときは，このファイルをコピーして最後の行を書き換える。
cd /d "%~dp0"
title run_bridge_A_phones3.bat
python sim_bridge.py --phones 3 --gui %*
pause
