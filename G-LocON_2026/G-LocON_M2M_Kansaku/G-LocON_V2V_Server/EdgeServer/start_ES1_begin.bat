@echo off
REM EdgeServer 1: ルート始点付近 (35.9515_139.6548) port=55601
cd /d "%~dp0"
java -cp "..\out\production\EdgeServer;..\SignalingServer\lib\java-json.jar" edge_server.StartUp 35.9515_139.6548 55601
pause
