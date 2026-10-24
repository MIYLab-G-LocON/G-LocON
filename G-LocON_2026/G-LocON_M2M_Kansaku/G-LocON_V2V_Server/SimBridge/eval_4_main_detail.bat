@echo off
rem Formal evaluation with raw data: same 60 runs as eval_2_main.bat, plus per-second positions, connection records, etc.
rem Results go to results\eval_detail (not results\eval), so the earlier results are kept. About 1 GB. Safe to stop and re-run.
cd /d "%~dp0"
title eval_4_main_detail.bat
python eval_run.py --plan main --jobs 4 --detail --root results\eval_detail %*
python eval_report.py --plan main --root results\eval_detail
python eval_cdf.py --plan main --root results\eval_detail
echo.
echo Results: results\eval_detail\main\report
echo CDF:     results\eval_detail\main\report_cdf
echo Raw data: results\eval_detail\main\raw
pause
