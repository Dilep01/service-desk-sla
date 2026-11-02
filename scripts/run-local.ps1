param(
 [string]$JavaHome='C:\Program Files\Java\jdk-21.0.12',
 [string]$PostgresBin='C:\Program Files\PostgreSQL\17\bin',
 [int]$Port=8080
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
if(-not(Test-Path "$JavaHome/bin/java.exe")){throw 'Supply -JavaHome with a Java 21 JDK directory.'}
$env:JAVA_HOME=$JavaHome
$env:PATH="$JavaHome\bin;$env:PATH"
$maven=Join-Path $root '.tools/apache-maven-3.9.9/bin/mvn.cmd'
if(-not(Test-Path $maven)){
 New-Item -ItemType Directory -Force '.tools'|Out-Null
 $url='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
 Invoke-WebRequest $url -OutFile '.tools/maven.zip'
 $expected=(Invoke-WebRequest "$url.sha512").Content.Trim().Split(' ')[0]
 if((Get-FileHash '.tools/maven.zip' -Algorithm SHA512).Hash -ne $expected){throw 'Maven checksum mismatch'}
 Expand-Archive -LiteralPath '.tools/maven.zip' -DestinationPath '.tools'
}
if(-not(Test-Path '.tools/pgdata/PG_VERSION')){& "$PostgresBin/initdb.exe" -D '.tools/pgdata' -U servicedesk --auth=trust --encoding=UTF8;if($LASTEXITCODE){throw 'Database initialization failed'}}
& "$PostgresBin/pg_isready.exe" -h 127.0.0.1 -p 55439
if($LASTEXITCODE){& "$PostgresBin/pg_ctl.exe" -D '.tools/pgdata' -l '.tools/postgres.log' -o '-h 127.0.0.1 -p 55439' start;if($LASTEXITCODE){throw 'Database startup failed'}}
$exists=& "$PostgresBin/psql.exe" -h 127.0.0.1 -p 55439 -U servicedesk -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='servicedesk'"
if($exists -ne '1'){& "$PostgresBin/createdb.exe" -h 127.0.0.1 -p 55439 -U servicedesk servicedesk}
Push-Location frontend
try {
 $node=(Get-Command node.exe).Source
 $npm=Join-Path (Split-Path $node) 'node_modules/npm/bin/npm-cli.js'
 & $node $npm ci --no-audit --no-fund
 if($LASTEXITCODE){throw 'Frontend install failed'}
 & $node node_modules/typescript/bin/tsc -b
 if($LASTEXITCODE){throw 'Type checking failed'}
 & $node node_modules/vite/bin/vite.js build
 if($LASTEXITCODE){throw 'Frontend build failed'}
}finally{Pop-Location}
$static=Join-Path $root 'backend/src/main/resources/static'
if((Test-Path $static) -and ([IO.Path]::GetFullPath($static) -eq [IO.Path]::GetFullPath((Join-Path $root 'backend/src/main/resources/static')))){Remove-Item -LiteralPath $static -Recurse -Force}
New-Item -ItemType Directory -Force $static|Out-Null
Copy-Item frontend/dist/* backend/src/main/resources/static -Recurse -Force
& $maven -f backend/pom.xml package -DskipTests
if($LASTEXITCODE){throw 'Backend build failed'}
$env:DB_URL='jdbc:postgresql://127.0.0.1:55439/servicedesk'
$env:SPRING_PROFILES_ACTIVE='demo'
$env:MAIL_PORT='11025'
$env:DESK_MAIL_ENABLED='true'
if(Test-Path '.tools/mailpit/mailpit.exe'){
 if(-not(Get-NetTCPConnection -LocalPort 18025 -State Listen -ErrorAction SilentlyContinue)){Start-Process '.tools/mailpit/mailpit.exe' -ArgumentList '--listen','127.0.0.1:18025','--smtp','127.0.0.1:11025' -WindowStyle Hidden}
}
Write-Host "Application: http://localhost:$Port; optional mail inbox: http://localhost:18025"
& "$JavaHome/bin/java.exe" -jar backend/target/service-desk-0.1.0.jar "--server.port=$Port" --server.address=127.0.0.1
