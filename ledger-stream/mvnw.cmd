@REM ----------------------------------------------------------------------------
@REM Maven Wrapper Script for Windows
@REM ----------------------------------------------------------------------------

@IF "%DEBUG%" == "" @ECHO OFF
@SETLOCAL

SET ERROR_CODE=0

@REM Set local scope for the variables with windows NT shell
IF "%OS%"=="Windows_NT" @SETLOCAL

@REM Find the project base dir
SET MAVEN_PROJECTBASEDIR=%~dp0
IF "%MAVEN_PROJECTBASEDIR:~-1%"=="\" SET MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%

@REM Check for wrapper properties
SET WRAPPER_PROP_FILE=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties
IF NOT EXIST "%WRAPPER_PROP_FILE%" (
  ECHO Error: Could not find %WRAPPER_PROP_FILE%
  GOTO error
)

@REM Extract distributionUrl
FOR /F "tokens=1,2 delims==" %%A IN ('findstr /R /C:"^distributionUrl" "%WRAPPER_PROP_FILE%"') DO SET MVN_DIST_URL=%%B

IF "%MVN_DIST_URL%"=="" (
  ECHO Error: Could not find distributionUrl in %WRAPPER_PROP_FILE%
  GOTO error
)

SET MVN_USER_HOME=%USERPROFILE%\.m2\wrapper\dists
SET MVN_DIST_DIR=%MVN_USER_HOME%\apache-maven-3.9.9
SET MVN_EXE=%MVN_DIST_DIR%\bin\mvn.cmd

IF EXIST "%MVN_EXE%" GOTO run

ECHO Downloading Maven from %MVN_DIST_URL% ...
IF NOT EXIST "%MVN_USER_HOME%" MKDIR "%MVN_USER_HOME%"
powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; $zip = '%MVN_USER_HOME%\maven.zip'; Invoke-WebRequest -Uri '%MVN_DIST_URL%' -OutFile $zip; Expand-Archive -Path $zip -DestinationPath '%MVN_USER_HOME%'; Move-Item -Path '%MVN_USER_HOME%\apache-maven-3.9.9*' -Destination '%MVN_DIST_DIR%' -Force; Remove-Item $zip -Force"

IF NOT EXIST "%MVN_EXE%" (
  ECHO Error: Failed to extract Maven to %MVN_DIST_DIR%
  GOTO error
)

:run
CALL "%MVN_EXE%" %*
IF ERRORLEVEL 1 GOTO error
GOTO end

:error
SET ERROR_CODE=1

:end
@ENDLOCAL
EXIT /B %ERROR_CODE%
