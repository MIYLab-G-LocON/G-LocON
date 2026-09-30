@echo off
rem Start all servers (STUN, MasterServer, 10 edge servers for SUMO)
rem Double-click to run. Moves to this folder first, then runs the command.
rem To add options (e.g. --join-eta 30), copy this file and edit the python line.
cd /d "%~dp0"
title run_servers.bat
python start_servers.py --stun %*
pause
