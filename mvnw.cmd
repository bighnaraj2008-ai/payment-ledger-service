@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script
@REM ----------------------------------------------------------------------------
@IF "%DEBUG%" == "" @ECHO OFF
@SETLOCAL EnableDelayedExpansion

SET MAVEN_JAVA_EXE="%JAVA_HOME%\bin\java.exe"
IF NOT DEFINED JAVA_HOME (
  SET MAVEN_JAVA_EXE=java.exe
)

SET WRAPPER_JAR="%~dp0.mvn\wrapper\maven-wrapper.jar"
SET WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain

%MAVEN_JAVA_EXE% -version >nul 2>&1
IF ERRORLEVEL 1 (
  echo Error: JAVA_HOME is not set and no 'java' command could be found in your PATH.
  exit /b 1
)

%MAVEN_JAVA_EXE% -jar %WRAPPER_JAR% %*
