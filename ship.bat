@echo off
rem Local release check: stop at the first failure, test before packaging.
rem "call" is required: without it the script ends after the first gradlew.bat.
cd /d "%~dp0"
call gradlew.bat clean test || exit /b 1
call gradlew.bat buildPlugin || exit /b 1
call gradlew.bat verifyPlugin || exit /b 1
