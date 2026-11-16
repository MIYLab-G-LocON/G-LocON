@echo off
rem Formal evaluation: main comparison (60 runs, several hours). Safe to stop and re-run: finished runs are skipped.
rem ETA by straight-line distance (the app before 2026-11-16; E14-E16). For the current app use eval_6/eval_8.
cd /d "%~dp0"
title eval_2_main.bat
python eval_run.py --plan main --jobs 4 --eta-by line %*
python eval_report.py --plan main
echo.
echo Results: results\eval\main\report
pause
