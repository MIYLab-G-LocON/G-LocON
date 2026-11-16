@echo off
rem Formal evaluation with raw data, ETA by distance ALONG THE ROUTE (the app from 2026-11-16).
rem Same 60 runs as eval_4_main_detail.bat. The proposed scheme is computed for tau = 15 s and 20 s on the same runs.
rem Results go to results\eval_detail_path (the earlier results are kept).
rem About 1 GB and a few hours. Safe to stop and re-run: finished runs are skipped.
cd /d "%~dp0"
title eval_6_main_path.bat
python eval_run.py --plan main --jobs 4 --detail --root results\eval_detail_path --eta-by path --taus 15,20 %*
python eval_report.py --plan main --root results\eval_detail_path
python eval_cdf.py --plan main --root results\eval_detail_path
echo.
echo Results: results\eval_detail_path\main\report
echo CDF:     results\eval_detail_path\main\report_cdf
echo Raw data: results\eval_detail_path\main\raw
pause
