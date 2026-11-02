param([Parameter(Mandatory=$true)][string]$Backup,[string]$PostgresBin='C:\Program Files\PostgreSQL\17\bin',[int]$Port=55439)
$ErrorActionPreference='Stop'
$resolved=(Resolve-Path -LiteralPath $Backup).Path
$target='servicedesk_restore_'+[Guid]::NewGuid().ToString('N').Substring(0,10)
& "$PostgresBin/createdb.exe" -h 127.0.0.1 -p $Port -U servicedesk $target
if($LASTEXITCODE){throw 'Could not create fresh restore database'}
& "$PostgresBin/pg_restore.exe" -h 127.0.0.1 -p $Port -U servicedesk -d $target --exit-on-error $resolved
if($LASTEXITCODE){throw "Restore failed in $target"}
& "$PostgresBin/psql.exe" -h 127.0.0.1 -p $Port -U servicedesk -d $target -v ON_ERROR_STOP=1 -c 'SELECT count(*) AS tickets FROM ticket; SELECT count(*) AS attachments,COALESCE(sum(octet_length(content)),0) AS attachment_bytes FROM ticket_attachment; SELECT version,success FROM flyway_schema_history ORDER BY installed_rank;'
if($LASTEXITCODE){throw 'Restore verification query failed'}
Write-Output "Restored into separate database $target. Existing databases were not overwritten."
