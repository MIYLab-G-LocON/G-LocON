@echo off
rem Show one run in sumo-gui (for explanation). Results: out\compare_demo\schemes.csv
cd /d "%~dp0"
title eval_0_demo_gui.bat
python compare_schemes.py --tag demo --etas 15:100:100 --radii 100,150,200 --gps-noise 5 --gps-corr 10 --gui %*
pause
