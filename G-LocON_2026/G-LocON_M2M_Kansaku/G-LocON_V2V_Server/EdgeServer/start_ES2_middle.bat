@echo off
REM EdgeServer 2: ルート中間 (35.9463_139.6533) port=55616
cd /d "%~dp0"
java -cp "..\out\production\EdgeServer;..\SignalingServer\lib\java-json.jar" edge_server.StartUp 35.9463_139.6533 55616
pause
