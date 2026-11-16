@echo off
rem Create double-click launchers on the desktop for the SimBridge batch files.
rem Run this once after cloning (or after moving the repository).
for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "[Environment]::GetFolderPath('Desktop')"`) do set DESK=%%D
> "%DESK%\GLocON_1_servers.bat" echo @echo off
>> "%DESK%\GLocON_1_servers.bat" echo call "%~dp0run_servers.bat"
> "%DESK%\GLocON_1_servers_fixed_route.bat" echo @echo off
>> "%DESK%\GLocON_1_servers_fixed_route.bat" echo call "%~dp0run_servers_fixed_route.bat"
> "%DESK%\GLocON_2_sumo_phone1_virtual.bat" echo @echo off
>> "%DESK%\GLocON_2_sumo_phone1_virtual.bat" echo call "%~dp0run_bridge_B_virtual.bat"
> "%DESK%\GLocON_2_sumo_phone1_virtual_control.bat" echo @echo off
>> "%DESK%\GLocON_2_sumo_phone1_virtual_control.bat" echo call "%~dp0run_bridge_C_control.bat"
> "%DESK%\GLocON_2_sumo_phones3.bat" echo @echo off
>> "%DESK%\GLocON_2_sumo_phones3.bat" echo call "%~dp0run_bridge_A_phones3.bat"
> "%DESK%\GLocON_2_sumo_pc_only.bat" echo @echo off
>> "%DESK%\GLocON_2_sumo_pc_only.bat" echo call "%~dp0run_bridge_pc_only.bat"
> "%DESK%\GLocON_3_eval_5_cdf_figs.bat" echo @echo off
>> "%DESK%\GLocON_3_eval_5_cdf_figs.bat" echo call "%~dp0eval_5_cdf_figs.bat"
> "%DESK%\GLocON_3_eval_6_main_path.bat" echo @echo off
>> "%DESK%\GLocON_3_eval_6_main_path.bat" echo call "%~dp0eval_6_main_path.bat"
> "%DESK%\GLocON_3_eval_7_cdf_figs_path.bat" echo @echo off
>> "%DESK%\GLocON_3_eval_7_cdf_figs_path.bat" echo call "%~dp0eval_7_cdf_figs_path.bat"
> "%DESK%\GLocON_3_eval_8_params_path.bat" echo @echo off
>> "%DESK%\GLocON_3_eval_8_params_path.bat" echo call "%~dp0eval_8_params_path.bat"
echo Created launchers on %DESK%:
dir /b "%DESK%\GLocON_*.bat"
pause
