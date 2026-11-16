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
python eval_cdf_figs.py --hist results\eval_detail_path\main\report_cdf\cdf_hist.csv --proposed eta_t15_j100_d100 --out results\eval_detail_path\main\report_cdf\figs_t15
python eval_cdf_figs.py --hist results\eval_detail_path\main\report_cdf\cdf_hist.csv --proposed eta_t20_j100_d100 --out results\eval_detail_path\main\report_cdf\figs_t20
echo.
echo fig_cdf.pdf = CDF (log axis for duration), fig_bars.pdf = control messages and decision margin (bars)
echo Figures and numbers: results\eval_detail_path\main\report_cdf\figs_t15 and figs_t20
start "" "results\eval_detail_path\main\report_cdf"
pause
