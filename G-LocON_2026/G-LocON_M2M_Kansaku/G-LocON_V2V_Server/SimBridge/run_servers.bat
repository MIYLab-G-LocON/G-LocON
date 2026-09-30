@echo off
chcp 65001 >nul
rem サーバ一式（STUN・MasterServer・エッジサーバ10か所）を起動する
rem ダブルクリックで起動できる（このファイルのあるフォルダに移動してから実行する）。
rem 引数を足したいときは，このファイルをコピーして最後の行を書き換える。
cd /d "%~dp0"
title run_servers.bat
python start_servers.py --stun %*
pause
