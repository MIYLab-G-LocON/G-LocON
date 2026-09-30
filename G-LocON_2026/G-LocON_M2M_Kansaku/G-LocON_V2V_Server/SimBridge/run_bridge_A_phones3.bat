@echo off
rem Evaluation 2: SUMO + 3 phones (mode A)
rem Double-click to run. Moves to this folder first, then runs the command.
rem To add options (e.g. --join-eta 30), copy this file and edit the python line.
cd /d "%~dp0"
title run_bridge_A_phones3.bat
python sim_bridge.py --phones 3 --gui %*
pause
