@echo off
rem PC only (no phone)
rem Double-click to run. Moves to this folder first, then runs the command.
rem To add options (e.g. --join-eta 30), copy this file and edit the python line.
cd /d "%~dp0"
title run_bridge_pc_only.bat
python sim_bridge.py --phones 0 --virtual --gui --local %*
pause
