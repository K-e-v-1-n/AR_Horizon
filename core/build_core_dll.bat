@echo off
rem Builds a small C-ABI DLL so desktop tools can call the same skyline fit
rem that runs on the phone. Needs MSVC.
setlocal
cd /d "%~dp0"
if not exist build mkdir build
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSPATH=%%i"
call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul
cl /nologo /LD /std:c++17 /O2 /EHsc /W3 /Iinclude src\core.cpp ^
   /Fe:build\vantage_core.dll /link /EXPORT:vt_fit_skyline
if errorlevel 1 exit /b 1
echo built build\vantage_core.dll
