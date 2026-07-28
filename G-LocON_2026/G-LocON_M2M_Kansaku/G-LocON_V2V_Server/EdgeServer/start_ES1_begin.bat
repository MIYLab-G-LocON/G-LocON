@echo off
REM EdgeServer 1: ルート始点付近 (35.95151_139.65476) port=55601
cd /d "%~dp0"
java -cp "..\out\production\EdgeServer;..\SignalingServer\lib\java-json.jar" edge_server.StartUp 35.95151_139.65476 55601
pause
