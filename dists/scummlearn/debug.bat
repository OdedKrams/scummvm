@echo off
rem ============================================================
rem  ScummLearn - tablet debug helper
rem  1. installs the newest APK on the tablet (USB cable + USB debugging on)
rem  2. starts the game and records the log while you play
rem  3. saves logs + the game's line log + a screenshot to debug-logs\
rem  Claude reads debug-logs\ afterwards. Nothing here needs a login.
rem ============================================================
setlocal
cd /d "%~dp0"
set "ADB=%~dp0platform-tools\adb.exe"
set "PKG=org.scummvm.scummvm.debug"
set "OUT=%~dp0debug-logs"
set "APK_URL=https://github.com/OdedKrams/scummvm/releases/download/scummlearn-latest/ScummVM-debug.apk"
if not exist "%OUT%" mkdir "%OUT%"

if not exist "%ADB%" (
  echo [1/6] Downloading Android platform-tools from Google ^(free, one time^)...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile '%~dp0pt.zip'; Expand-Archive -Force '%~dp0pt.zip' '%~dp0'; Remove-Item '%~dp0pt.zip'"
)
if not exist "%ADB%" (
  echo Could not download platform-tools. Check the internet connection and try again.
  pause & exit /b 1
)

echo [2/6] Looking for the tablet...
"%ADB%" start-server >nul 2>&1
"%ADB%" devices > "%OUT%\devices.txt"
findstr /R /C:"	device$" "%OUT%\devices.txt" >nul
if errorlevel 1 (
  type "%OUT%\devices.txt"
  echo.
  echo No tablet found. On the tablet: Settings ^> About ^> tap "Build number" 7 times,
  echo then Settings ^> Developer options ^> turn on "USB debugging".
  echo Connect the cable and tap "Allow" on the tablet, then run this file again.
  pause & exit /b 1
)

echo [3/6] Downloading the newest ScummLearn APK...
curl.exe -L -f -s -o "%OUT%\ScummVM-debug.apk.new" "%APK_URL%"
if not errorlevel 1 move /y "%OUT%\ScummVM-debug.apk.new" "%OUT%\ScummVM-debug.apk" >nul
if not exist "%OUT%\ScummVM-debug.apk" (
  echo Could not download the APK. Try again later.
  pause & exit /b 1
)

echo [4/6] Installing on the tablet...
"%ADB%" install -r -d "%OUT%\ScummVM-debug.apk" > "%OUT%\install.txt" 2>&1
type "%OUT%\install.txt"
findstr /C:"Success" "%OUT%\install.txt" >nul
if not errorlevel 1 goto installed

rem The installed app was signed with a different key (older builds used a new key each
rem time), so Android won't update it. Keep the child's progress and the game list,
rem remove the old app, install the new one and put the progress back.
echo.
echo The old version can't be updated in place. Saving progress, then reinstalling...
"%ADB%" exec-out run-as %PKG% tar cf - shared_prefs files > "%OUT%\progress-backup.tar"
for %%F in ("%OUT%\progress-backup.tar") do if %%~zF LSS 1024 echo (no progress found to save)
"%ADB%" uninstall %PKG%
"%ADB%" install "%OUT%\ScummVM-debug.apk" > "%OUT%\install.txt" 2>&1
type "%OUT%\install.txt"
findstr /C:"Success" "%OUT%\install.txt" >nul
if errorlevel 1 (
  echo Install failed. Send Claude the file debug-logs\install.txt
  pause & exit /b 1
)
for %%F in ("%OUT%\progress-backup.tar") do if %%~zF GEQ 1024 (
  "%ADB%" push "%OUT%\progress-backup.tar" /data/local/tmp/sl-progress.tar >nul
  "%ADB%" shell "cat /data/local/tmp/sl-progress.tar | run-as %PKG% tar xf -"
  "%ADB%" shell rm /data/local/tmp/sl-progress.tar
  echo Progress restored.
)
echo If the game is missing from the list, add it again with "Add Game" ^(same folder as before^).

:installed

rem ---- Full game (if it is in games\comi next to this file): copy it to the tablet once,
rem ---- and its learning pack (translations) every time, so updates reach the tablet.
set "GAME=%~dp0games\comi"
set "TAB=/sdcard/ScummLearn/comi"
if not exist "%GAME%\COMI.LA2" goto nofullgame
"%ADB%" shell "ls %TAB%/ 2>/dev/null" > "%OUT%\tablet-comi-before.txt"
findstr /C:"COMI.LA2" "%OUT%\tablet-comi-before.txt" >nul
if not errorlevel 1 goto fullgamethere
echo.
echo Copying The Curse of Monkey Island to the tablet ^(one time, a few minutes^)...
"%ADB%" shell mkdir -p %TAB% > "%OUT%\comi-copy.txt" 2>&1
"%ADB%" push "%GAME%\COMI.LA0" %TAB%/COMI.LA0 >> "%OUT%\comi-copy.txt" 2>&1
"%ADB%" push "%GAME%\COMI.LA1" %TAB%/COMI.LA1 >> "%OUT%\comi-copy.txt" 2>&1
"%ADB%" push "%GAME%\COMI.LA2" %TAB%/COMI.LA2 >> "%OUT%\comi-copy.txt" 2>&1
"%ADB%" push "%GAME%\RESOURCE" %TAB%/ >> "%OUT%\comi-copy.txt" 2>&1
type "%OUT%\comi-copy.txt"
:fullgamethere
if exist "%GAME%\learn_pack.json" "%ADB%" push "%GAME%\learn_pack.json" %TAB%/learn_pack.json >> "%OUT%\comi-copy.txt" 2>&1
"%ADB%" shell "ls -l %TAB%/" > "%OUT%\tablet-comi.txt" 2>&1
findstr /C:"COMI.LA2" "%OUT%\tablet-comi.txt" >nul
if errorlevel 1 (
  echo.
  echo *** Could not copy the full game to the tablet. Send Claude: debug-logs\comi-copy.txt
) else (
  echo.
  echo The full game is on the tablet, in the folder: ScummLearn\comi
  echo If it is not in ScummVM's list yet: Add Game, then choose that folder.
)
:nofullgame

echo [5/6] Starting the game. Play now.
"%ADB%" logcat -c
start "ScummLearn log" /min cmd /c ""%ADB%" logcat -v time > "%OUT%\logcat-full.txt""
"%ADB%" shell monkey -p %PKG% -c android.intent.category.LAUNCHER 1 >nul 2>&1
echo.
echo    When you are done playing (or something went wrong),
echo    come back here and press any key.
pause >nul

echo [6/6] Collecting logs...
"%ADB%" exec-out screencap -p > "%OUT%\screen.png"
"%ADB%" shell run-as %PKG% cat files/saves/learn.jsonl > "%OUT%\learn.jsonl" 2>nul
"%ADB%" shell dumpsys package %PKG% | findstr /C:"versionName" /C:"lastUpdateTime" > "%OUT%\app-version.txt"
"%ADB%" kill-server >nul 2>&1
findstr /I /C:"ScummLearn" /C:"scummvm" /C:"AndroidRuntime" /C:"FATAL" /C:"DEBUG  " "%OUT%\logcat-full.txt" > "%OUT%\logcat.txt"
echo %date% %time% > "%OUT%\collected-at.txt"
echo.
echo Done. Logs are in: %OUT%
echo Tell Claude "check the debug logs".
pause
