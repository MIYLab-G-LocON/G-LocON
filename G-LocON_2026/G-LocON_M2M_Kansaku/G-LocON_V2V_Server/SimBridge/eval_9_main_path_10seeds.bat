@echo off
rem Add seeds 6-10 to the formal evaluation (ETA along the route), positioning error 5 m only (30 runs).
rem Seeds 1-5 from eval_6_main_path.bat are kept and skipped, so the paper conditions get 10 seeds each.
rem Then: report, CDF, and the paper figures (tau = 20 s and 15 s).
rem About 0.5 GB and 2-3 hours. Safe to stop and re-run: finished runs are skipped.
cd /d "%~dp0"
title eval_9_main_path_10seeds.bat
python eval_run.py --plan main --jobs 4 --detail --root results\eval_detail_path --eta-by path --taus 15,20 --seeds 1-10 --noises gps5slow %*
python eval_report.py --plan main --root results\eval_detail_path
python eval_cdf.py --plan main --root results\eval_detail_path
python -c "import matplotlib" 2>nul
if errorlevel 1 (
  echo matplotlib is not installed. Run setup_matplotlib.bat, then eval_7_cdf_figs_path.bat.
  pause
  exit /b 1
)
python eval_cdf_figs.py --hist results\eval_detail_path\main\report_cdf\cdf_hist.csv --proposed eta_t20_j100_d100 --out results\eval_detail_path\main\report_cdf\figs_t20
python eval_cdf_figs.py --hist results\eval_detail_path\main\report_cdf\cdf_hist.csv --proposed eta_t15_j100_d100 --out results\eval_detail_path\main\report_cdf\figs_t15
echo.
echo Results: results\eval_detail_path\main\report  (5 m error: 10 seeds, no error: 5 seeds)
echo Figures: results\eval_detail_path\main\report_cdf\figs_t20
pause
