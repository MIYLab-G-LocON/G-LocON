@echo off
REM EdgeServer 2: ルート中間 (35.94627_139.65333) port=55616
cd /d "%~dp0"
java -cp "..\out\production\EdgeServer;..\SignalingServer\lib\java-json.jar" edge_server.StartUp 35.94627_139.65333 55616
pause
