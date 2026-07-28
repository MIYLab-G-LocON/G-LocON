@echo off
REM EdgeServer 3: ルート終盤 (35.94763_139.64549) port=55625
cd /d "%~dp0"
java -cp "..\out\production\EdgeServer;..\SignalingServer\lib\java-json.jar" edge_server.StartUp 35.94763_139.64549 55625
pause
