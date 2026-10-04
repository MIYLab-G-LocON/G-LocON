@echo off
rem Formal evaluation: main comparison (60 runs, several hours). Safe to stop and re-run: finished runs are skipped.
cd /d "%~dp0"
title eval_2_main.bat
python eval_run.py --plan main --jobs 4 %*
python eval_report.py --plan main
echo.
echo Results: results\eval\main\report
pause
