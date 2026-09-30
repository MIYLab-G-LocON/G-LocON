@echo off
rem Start servers for the fixed route (evaluation 1: ES1-ES3)
rem Double-click to run. Moves to this folder first, then runs the command.
rem To add options (e.g. --join-eta 30), copy this file and edit the python line.
cd /d "%~dp0"
title run_servers_fixed_route.bat
python start_servers.py --stun --csv ..\MasterServer\edge_servers.csv %*
pause
