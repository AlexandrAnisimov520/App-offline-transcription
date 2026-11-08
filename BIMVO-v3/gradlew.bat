@ECHO OFF
SETLOCAL
SET "APP_HOME=%~dp0"
PUSHD "%APP_HOME%"
IF NOT DEFINED JAVA_HOME (
    java -cp "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) ELSE (
    "%JAVA_HOME%\bin\java.exe" -cp "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
)
SET "EXIT_CODE=%ERRORLEVEL%"
POPD
ENDLOCAL & EXIT /B %EXIT_CODE%
