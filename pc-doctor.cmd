@echo off
rem Avvia PC Doctor. Compila il progetto se il jar non esiste ancora.
setlocal
set "HERE=%~dp0"
chcp 65001 >nul
if not exist "%HERE%target\pc-doctor.jar" (
    echo Compilazione in corso...
    call "%HERE%mvnw.cmd" -q -B -DskipTests package || exit /b 1
)
java -XX:MaxRAMPercentage=75 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%HERE%target\pc-doctor.jar" %*
