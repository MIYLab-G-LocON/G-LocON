@echo off
rem Formal evaluation: quick check (2 runs, a few minutes), then tables and figures.
cd /d "%~dp0"
title eval_1_quick.bat
python eval_run.py --plan quick %*
python eval_report.py --plan quick
echo.
echo Results: results\eval\quick\report
pause
