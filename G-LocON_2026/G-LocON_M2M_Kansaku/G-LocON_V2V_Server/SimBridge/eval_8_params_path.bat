@echo off
rem tau / rho / delta one at a time (5 runs), ETA along the route (the app from 2026-11-16).
rem Results go to results\eval_path\params.
cd /d "%~dp0"
title eval_8_params_path.bat
python eval_run.py --plan params --jobs 4 --eta-by path %*
python eval_report.py --plan params --root results\eval_path
echo.
echo Results: results\eval_path\params\report
pause
