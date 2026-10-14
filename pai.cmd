@echo off
rem Starts pai with native access enabled (JLine terminal on JDK 21). Build first: mvn -q package
setlocal
set "PAI_JAR=%~dp0pai-cli\target\pai-cli.jar"
if not exist "%PAI_JAR%" (
  echo pai: %PAI_JAR% not found; run mvn -q package first 1>&2
  exit /b 1
)
java --enable-native-access=ALL-UNNAMED %PAI_JAVA_OPTS% -jar "%PAI_JAR%" %*
exit /b %ERRORLEVEL%
