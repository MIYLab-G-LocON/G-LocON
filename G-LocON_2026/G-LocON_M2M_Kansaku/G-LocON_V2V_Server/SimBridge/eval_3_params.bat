@echo off
rem Formal evaluation: parameters of the proposed scheme (5 runs).
cd /d "%~dp0"
title eval_3_params.bat
python eval_run.py --plan params --jobs 4 %*
python eval_report.py --plan params
echo.
echo Results: results\eval\params\report
pause
