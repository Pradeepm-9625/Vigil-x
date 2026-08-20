@echo off
setlocal

cd /d "C:\Users\Admin\Downloads\Vigil-x-main\Vigil-x-main"

echo ======================================== > soak-task.log
echo Vigil-X Health Soak Test >> soak-task.log
echo Started: %date% %time% >> soak-task.log
echo ======================================== >> soak-task.log

echo Working Directory: %CD% >> soak-task.log
echo JAVA_HOME: %JAVA_HOME% >> soak-task.log
echo MAVEN_HOME: %MAVEN_HOME% >> soak-task.log

where java >> soak-task.log 2>&1
where mvn >> soak-task.log 2>&1

echo Starting Maven test... >> soak-task.log

call mvn -Dtest=SoakHealthCheckTest "-Dsoak.enabled=true" test >> soak-task.log 2>&1

set TEST_RESULT=%ERRORLEVEL%

echo. >> soak-task.log
echo ======================================== >> soak-task.log
echo Soak Test Finished: %date% %time% >> soak-task.log
echo Maven Exit Code: %TEST_RESULT% >> soak-task.log
echo ======================================== >> soak-task.log

exit /b %TEST_RESULT%