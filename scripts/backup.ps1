param([string]$PostgresBin='C:\Program Files\PostgreSQL\17\bin',[string]$Database='servicedesk',[int]$Port=55439)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$folder=Join-Path $root 'data/backups'
New-Item -ItemType Directory -Force $folder|Out-Null
$file=Join-Path $folder ((Get-Date -Format 'yyyyMMdd_HHmmss')+'.dump')
& "$PostgresBin/pg_dump.exe" -h 127.0.0.1 -p $Port -U servicedesk -d $Database -Fc -f $file
if($LASTEXITCODE){throw 'Backup failed'}
Get-FileHash -LiteralPath $file -Algorithm SHA256|Format-List
Write-Output $file
