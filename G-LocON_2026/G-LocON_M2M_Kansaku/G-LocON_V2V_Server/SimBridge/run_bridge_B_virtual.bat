@echo off
rem Evaluation 3: SUMO + virtual clients + 1 phone (mode B)
rem Double-click to run. Moves to this folder first, then runs the command.
rem To add options (e.g. --join-eta 30), copy this file and edit the python line.
cd /d "%~dp0"
title run_bridge_B_virtual.bat
python sim_bridge.py --phones 1 --virtual --gui --follow-phone %*
pause
