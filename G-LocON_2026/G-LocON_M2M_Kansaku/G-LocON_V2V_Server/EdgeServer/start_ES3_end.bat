@echo off
REM EdgeServer 3: ルート終盤 (35.9476_139.6455) port=55625
cd /d "%~dp0"
java -cp "..\out\production\EdgeServer;..\SignalingServer\lib\java-json.jar" edge_server.StartUp 35.9476_139.6455 55625
pause
