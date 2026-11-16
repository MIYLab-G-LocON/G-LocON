@echo off
rem CDF figures and numbers from the raw data of eval_6_main_path.bat (ETA along the route).
rem Needs matplotlib (run setup_matplotlib.bat once).
cd /d "%~dp0"
title eval_7_cdf_figs_path.bat
python -c "import matplotlib" 2>nul
if errorlevel 1 (
  echo matplotlib is not installed. Run setup_matplotlib.bat first.
  pause
  exit /b 1
)
python eval_cdf.py --plan main --root results\eval_detail_path
python eval_cdf_figs.py --hist results\eval_detail_path\main\report_cdf\cdf_hist.csv
echo.
echo Figures and numbers: results\eval_detail_path\main\report_cdf\figs
start "" "results\eval_detail_path\main\report_cdf\figs"
pause
