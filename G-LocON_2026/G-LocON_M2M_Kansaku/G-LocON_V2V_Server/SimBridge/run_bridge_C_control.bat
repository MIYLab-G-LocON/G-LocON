@echo off
rem Evaluation 3 with vehicle control: SUMO + virtual clients + 1 phone.
rem Sudden stops happen only when a follower exists; hazard info goes through the groups (P2P).
cd /d "%~dp0"
title run_bridge_C_control.bat
python sim_bridge.py --phones 1 --virtual --gui --follow-phone --control system --hazard-rule follower %*
pause
