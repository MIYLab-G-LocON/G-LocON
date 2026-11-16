@echo off
rem CDF of connection duration and number of peers, from the raw data of eval_4_main_detail.bat.
rem   1. eval_cdf.py      count the raw data -> results\eval_detail\main\report_cdf\cdf_hist.csv
rem   2. eval_cdf_figs.py draw the paper/report figures and write the numbers -> report_cdf\figs
rem Needs matplotlib for the figures (python -m pip install matplotlib).
cd /d "%~dp0"
title eval_5_cdf_figs.bat
python -c "import matplotlib" 2>nul
if errorlevel 1 (
  echo matplotlib is not installed. Run setup_matplotlib.bat first.
  pause
  exit /b 1
)
python eval_cdf.py --plan main --root results\eval_detail
python eval_cdf_figs.py --hist results\eval_detail\main\report_cdf\cdf_hist.csv
echo.
echo fig_cdf.pdf = CDF (log axis for duration), fig_bars.pdf = control messages and decision margin (bars)
echo Figures and numbers: results\eval_detail\main\report_cdf\figs
start "" "results\eval_detail\main\report_cdf\figs"
pause
