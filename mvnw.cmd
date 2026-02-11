@echo off
setlocal
set "WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar"
set "WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain"
set "MAVEN_PROJECTBASEDIR=%~dp0"
if "%MAVEN_PROJECTBASEDIR:~-1%"=="\" set "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"
if not defined JAVA_HOME (
  for /d %%D in ("C:\Program Files\Microsoft\jdk-*") do if not defined JAVA_HOME set "JAVA_HOME=%%~fD"
  for /d %%D in ("C:\Program Files\Java\jdk*") do if not defined JAVA_HOME set "JAVA_HOME=%%~fD"
  for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk*") do if not defined JAVA_HOME set "JAVA_HOME=%%~fD"
)
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java"
if not exist "%WRAPPER_JAR%" (
  echo Maven wrapper jar not found: %WRAPPER_JAR%
  exit /b 1
)
"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" %WRAPPER_LAUNCHER% %*
endlocal
