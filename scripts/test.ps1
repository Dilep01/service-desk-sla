param([string]$DatabaseUrl='jdbc:postgresql://127.0.0.1:55439/servicedesk_tests',[string]$JavaHome='C:\Program Files\Java\jdk-21.0.12')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
$env:JAVA_HOME=$JavaHome;$env:DB_URL=$DatabaseUrl;$env:RUN_DB_TESTS='true'
& '.tools/apache-maven-3.9.9/bin/mvn.cmd' -f backend/pom.xml test
if($LASTEXITCODE){throw 'Backend tests failed'}
& node frontend/node_modules/typescript/bin/tsc -b frontend
if($LASTEXITCODE){throw 'Type checking failed'}
& node frontend/node_modules/vite/bin/vite.js build frontend
if($LASTEXITCODE){throw 'Frontend build failed'}
