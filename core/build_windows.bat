@echo off
rem Builds the portable core for Windows with MSVC (no CMake required).
setlocal
cd /d "%~dp0"
if not exist build mkdir build

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
  echo error: vswhere not found; install Visual Studio Build Tools with C++.
  exit /b 1
)
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSPATH=%%i"
if not defined VSPATH (
  echo error: no MSVC toolchain found.
  exit /b 1
)
call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1

cl /nologo /std:c++17 /O2 /EHsc /W3 /utf-8 /Iinclude ^
   src\core.cpp tests\test_core.cpp /Fe:build\vantage-tests.exe /Fo:build\ || exit /b 1
cl /nologo /std:c++17 /O2 /EHsc /W3 /utf-8 /Iinclude ^
   src\core.cpp cli\main.cpp /Fe:build\vantage-cli.exe /Fo:build\ || exit /b 1

echo.
echo built build\vantage-tests.exe and build\vantage-cli.exe
