@echo off
rem Install matplotlib (for drawing figures) into the Python used by SimBridge. Needs internet.
cd /d "%~dp0"
title setup_matplotlib.bat
python -m pip install matplotlib
echo.
python -c "import matplotlib; print('matplotlib', matplotlib.__version__, 'OK')"
pause
