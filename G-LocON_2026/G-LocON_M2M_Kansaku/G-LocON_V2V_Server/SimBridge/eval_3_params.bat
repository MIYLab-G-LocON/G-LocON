@echo off
rem Formal evaluation: parameters of the proposed scheme (5 runs).
rem ETA by straight-line distance (the app before 2026-11-16; E14-E16). For the current app use eval_6/eval_8.
cd /d "%~dp0"
title eval_3_params.bat
python eval_run.py --plan params --jobs 4 --eta-by line %*
python eval_report.py --plan params
echo.
echo Results: results\eval\params\report
pause
