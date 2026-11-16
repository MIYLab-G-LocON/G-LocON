@echo off
rem Formal evaluation: quick check (2 runs, a few minutes), then tables and figures.
rem ETA by straight-line distance (the app before 2026-11-16; E14-E16). For the current app use eval_6/eval_8.
cd /d "%~dp0"
title eval_1_quick.bat
python eval_run.py --plan quick --eta-by line %*
python eval_report.py --plan quick
echo.
echo Results: results\eval\quick\report
pause
