@echo off
rem Avvia PC Doctor. Cerca un JDK 21+ (serve per compilare), compila il progetto se il jar non esiste ancora.
setlocal EnableDelayedExpansion
set "HERE=%~dp0"
chcp 65001 >nul

call :findjdk
if not defined JDK (
    echo.
    echo PC Doctor richiede il JDK 21 o superiore ^(il solo JRE non basta per compilare^).
    where winget >nul 2>nul
    if errorlevel 1 (
        echo Scaricalo da https://adoptium.net ^(Temurin 21, JDK^) e riavvia il terminale.
        exit /b 1
    )
    set /p "ANSWER=Vuoi installarlo ora con winget? (S/N) "
    if /i not "!ANSWER!"=="S" exit /b 1
    winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-package-agreements --accept-source-agreements
    call :findjdk
    if not defined JDK (
        echo Installazione non riuscita: chiudi e riapri il terminale, poi riprova.
        exit /b 1
    )
)
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"

if not exist "%HERE%target\pc-doctor.jar" (
    echo Prima compilazione in corso ^(serve internet, circa 1-2 minuti^)...
    call "%HERE%mvnw.cmd" -q -B -DskipTests -f "%HERE%pom.xml" package || exit /b 1
)
"%JDK%\bin\java.exe" -XX:MaxRAMPercentage=75 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%HERE%target\pc-doctor.jar" %*
exit /b %errorlevel%

rem Imposta JDK alla cartella di un JDK con versione >= 21 (JAVA_HOME, poi le cartelle di installazione comuni).
:findjdk
set "JDK="
if defined JAVA_HOME call :tryjdk "%JAVA_HOME%"
if defined JDK exit /b 0
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-*" "%ProgramFiles%\Java\jdk-*" "%ProgramFiles%\Microsoft\jdk-*" "%ProgramFiles%\Zulu\zulu-*" "%ProgramFiles%\Amazon Corretto\jdk*") do (
    if not defined JDK call :tryjdk "%%~D"
)
exit /b 0

:tryjdk
if not exist "%~1\bin\javac.exe" exit /b 0
set "VER="
for /f "tokens=2" %%V in ('"%~1\bin\javac.exe" -version 2^>^&1') do set "VER=%%V"
for /f "delims=." %%M in ("!VER!") do set "MAJOR=%%M"
if defined MAJOR if !MAJOR! GEQ 21 set "JDK=%~1"
exit /b 0
