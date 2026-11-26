param([switch]$Stop)
$ErrorActionPreference = 'Stop'

function Test-Port([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try { $client.Connect('127.0.0.1', $Port); return $true }
    catch { return $false }
    finally { $client.Dispose() }
}
function Get-OwnedBackend($StateFile, $Project) {
    if (-not (Test-Path -LiteralPath $StateFile)) { return $null }
    $state = Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json
    if ($state.project -ne $Project) { throw 'Backend state belongs to another project.' }
    $process = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    if ($process.ProcessName -ne 'java' -or $process.StartTime.ToUniversalTime().Ticks.ToString() -ne $state.startTicks) {
        throw 'Backend PID was reused; refusing to touch another process.'
    }
    return $process
}
function Read-EnvFile($File) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $File) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)=(.*)$') { $values[$matches[1]] = $matches[2].Trim() }
    }
    return $values
}
function Find-Jdk17 {
    $choices = @($env:JAVA_HOME)
    $choices += @(Get-ChildItem -Path "$env:USERPROFILE\.jdks\*17*", "$env:ProgramFiles\Java\*17*", "$env:ProgramFiles\Microsoft\*17*", "$env:ProgramFiles\Eclipse Adoptium\*17*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    foreach ($choice in $choices) {
        if ($choice -and (Test-Path -LiteralPath "$choice\bin\java.exe") -and (Test-Path -LiteralPath "$choice\release") -and
            (Select-String -LiteralPath "$choice\release" -Pattern '^JAVA_VERSION="17[.\-"]' -Quiet)) { return $choice }
    }
    throw 'Install JDK 17 or set JAVA_HOME to an installed JDK 17 directory.'
}

try {
    $project = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
    $runtime = Join-Path $project '.civa-local'
    $data = Join-Path $runtime 'postgres-data'
    $stateFile = Join-Path $runtime 'backend.json'
    $envFile = Join-Path $project '.env'
    $dbPort = 55433
    $dbName = 'civa_arch_dev'
    $dbUser = 'civa_local'
    $pgChoices = @(Get-ChildItem -Path "$env:ProgramFiles\PostgreSQL\*\bin\pg_ctl.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -ExpandProperty DirectoryName)
    $pgCommand = Get-Command pg_ctl.exe -ErrorAction SilentlyContinue
    if ($pgCommand) { $pgChoices = @((Split-Path $pgCommand.Source -Parent)) + $pgChoices }
    $pgBin = $pgChoices | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'initdb.exe') } | Select-Object -First 1
    if (-not $pgBin) { throw 'PostgreSQL command-line tools are missing. Install PostgreSQL or use start-civa.bat with Docker.' }
    $pgCtl = Join-Path $pgBin 'pg_ctl.exe'
    $owned = Get-OwnedBackend $stateFile $project
    if ($Stop) {
        if ($owned) { Stop-Process -Id $owned.Id; Write-Host 'Stopped this project backend.' }
        if (Test-Path -LiteralPath $stateFile) { Remove-Item -LiteralPath $stateFile }
        if (Test-Path -LiteralPath (Join-Path $data 'PG_VERSION')) {
            & $pgCtl status -D $data *> $null
            if ($LASTEXITCODE -eq 0) {
                & $pgCtl stop -D $data -m fast -w -t 45
                if ($LASTEXITCODE -ne 0) { throw 'Could not stop this project database.' }
            }
        }
        Write-Host 'Local services stopped; all database files and uploaded files are preserved.'
        exit 0
    }

    New-Item -ItemType Directory -Force -Path $runtime | Out-Null
    if (-not (Test-Path -LiteralPath $envFile)) {
        if (Test-Path -LiteralPath (Join-Path $data 'PG_VERSION')) { throw '.env is missing for an existing cluster; restore it rather than resetting the password.' }
        if (Test-Port $dbPort) { throw "Port $dbPort is already in use. No existing service was changed." }
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $bytes = New-Object byte[] 32; $random.GetBytes($bytes) }
        finally { $random.Dispose() }
        $password = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
        $lines = @('# Local native PostgreSQL; generated once. Do not share this file.',
            "POSTGRES_DB=$dbName", "POSTGRES_USER=$dbUser", "POSTGRES_PASSWORD=$password",
            "DB_URL=jdbc:postgresql://127.0.0.1:$dbPort/$dbName", "DB_USERNAME=$dbUser", "DB_PASSWORD=$password",
            'CIVA_MEDIA_DIR=', 'SERVER_PORT=8080', 'CIVA_NUTRITION_PROVIDER=stub')
        [IO.File]::WriteAllLines($envFile, [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
        Write-Host 'Created private .env for a new, separate local development database.'
    }
    $config = Read-EnvFile $envFile
    if ($config['DB_URL'] -ne "jdbc:postgresql://127.0.0.1:$dbPort/$dbName" -or $config['DB_USERNAME'] -ne $dbUser -or -not $config['DB_PASSWORD'] -or $config['SERVER_PORT'] -ne '8080') {
        throw 'Existing .env is configured for another stack. It was not overwritten; use its matching launcher.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $data 'PG_VERSION'))) {
        if (Test-Port $dbPort) { throw "Port $dbPort is occupied by another process." }
        $passwordFile = Join-Path $runtime 'init-password.local'
        try {
            [IO.File]::WriteAllText($passwordFile, $config['DB_PASSWORD'] + "`n", (New-Object Text.UTF8Encoding($false)))
            & (Join-Path $pgBin 'initdb.exe') -D $data -U $dbUser "--pwfile=$passwordFile" --encoding=UTF8 --locale=C --auth-host=scram-sha-256 --auth-local=scram-sha-256 *> (Join-Path $runtime 'initdb.log')
            if ($LASTEXITCODE -ne 0) { throw "Database initialization failed; see $runtime\initdb.log. Existing databases were not touched." }
        } finally {
            if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile }
        }
    }
    & $pgCtl status -D $data *> $null
    if ($LASTEXITCODE -ne 0) {
        if (Test-Port $dbPort) { throw "Port $dbPort is occupied; refusing to reuse an unrelated database." }
        # Detach the server's handles as well as its process, so callers that capture
        # this launcher's output do not wait for PostgreSQL to exit.
        $stdinFile = Join-Path $runtime 'empty-stdin.local'
        if (-not (Test-Path -LiteralPath $stdinFile)) { [IO.File]::WriteAllText($stdinFile, '') }
        $pgArguments = 'start -D "' + $data + '" -l "' + (Join-Path $runtime 'postgres.log') + '" -o "-p ' + $dbPort + ' -h 127.0.0.1" -w -t 45'
        $pgStart = Start-Process -FilePath $pgCtl -ArgumentList $pgArguments -WindowStyle Hidden -PassThru -RedirectStandardInput $stdinFile -RedirectStandardOutput (Join-Path $runtime 'pgctl-start.log') -RedirectStandardError (Join-Path $runtime 'pgctl-start-error.log')
        $null = $pgStart.Handle
        if (-not $pgStart.WaitForExit(50000)) { throw "Database start is still pending; see $runtime\pgctl-start.log." }
        if ($pgStart.ExitCode -ne 0) { throw "Database startup failed; see $runtime\postgres.log." }
    }
    $oldPgPassword = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $config['DB_PASSWORD']
        $databaseCheck = & (Join-Path $pgBin 'psql.exe') -h 127.0.0.1 -p $dbPort -U $dbUser -d postgres -w -At -c "SELECT datname FROM pg_database WHERE datname='$dbName'" 2> (Join-Path $runtime 'database-check.log')
        if ($LASTEXITCODE -ne 0) { throw 'Could not authenticate to the separate database. Password was not reset.' }
        if ($databaseCheck -ne $dbName) {
            & (Join-Path $pgBin 'createdb.exe') -h 127.0.0.1 -p $dbPort -U $dbUser -w $dbName
            if ($LASTEXITCODE -ne 0) { throw 'Could not create the development database.' }
        }
    } finally { $env:PGPASSWORD = $oldPgPassword }
    Write-Host "Project database ready at 127.0.0.1:$dbPort (existing 5432 service unchanged)."
    if ($owned -and (Test-Port 8080)) {
        Write-Host 'Reusing this project backend at http://127.0.0.1:8080/.'
        Write-Host 'Return to Android Studio and Run app. Register your own account on first use.'
        exit 0
    }
    if (Test-Port 8080) { throw 'Port 8080 belongs to another backend. No unrelated process was stopped.' }
    if ($owned) { throw 'An owned backend is still starting. Check the backend log before retrying.' }
    $env:JAVA_HOME = Find-Jdk17
    $jar = Join-Path $project 'server\build\libs\civa-server.jar'
    $newest = Get-ChildItem -LiteralPath (Join-Path $project 'server\src'), (Join-Path $project 'contracts') -File -Recurse | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    $buildFile = Get-Item -LiteralPath (Join-Path $project 'server\build.gradle')
    if (-not (Test-Path -LiteralPath $jar) -or (Get-Item -LiteralPath $jar).LastWriteTimeUtc -lt $newest.LastWriteTimeUtc -or (Get-Item -LiteralPath $jar).LastWriteTimeUtc -lt $buildFile.LastWriteTimeUtc) {
        Write-Host 'Building backend with JDK 17...'
        Push-Location (Join-Path $project 'server')
        try {
            $buildArgs = @('bootJar', '--console=plain')
            $mirror = Join-Path $runtime 'server-maven.init.gradle'
            if (Test-Path -LiteralPath $mirror) { $buildArgs += @('-I', $mirror) }
            & '.\gradlew.bat' @buildArgs
            if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
        }
        finally { Pop-Location }
    }
    $media = Join-Path $runtime 'media'
    New-Item -ItemType Directory -Force -Path $media | Out-Null
    $childEnv = @{'DB_URL'=$config['DB_URL']; 'DB_USERNAME'=$config['DB_USERNAME']; 'DB_PASSWORD'=$config['DB_PASSWORD']; 'CIVA_MEDIA_DIR'=$media; 'SERVER_PORT'='8080'; 'SERVER_ADDRESS'='127.0.0.1'}
    foreach ($key in $config.Keys) { if ($key.StartsWith('CIVA_') -and $key -ne 'CIVA_MEDIA_DIR') { $childEnv[$key] = $config[$key] } }
    $previous = @{}
    try {
        foreach ($key in $childEnv.Keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process'); [Environment]::SetEnvironmentVariable($key, $childEnv[$key], 'Process') }
        $stdinFile = Join-Path $runtime 'backend-stdin.local'
        if (-not (Test-Path -LiteralPath $stdinFile)) { [IO.File]::WriteAllText($stdinFile, '') }
        $backend = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList @('-jar', ('"' + $jar + '"')) -WorkingDirectory (Join-Path $project 'server') -WindowStyle Hidden -PassThru -RedirectStandardInput $stdinFile -RedirectStandardOutput (Join-Path $runtime 'backend.log') -RedirectStandardError (Join-Path $runtime 'backend-error.log')
        $null = $backend.Handle
        @{project=$project; processId=$backend.Id; startTicks=$backend.StartTime.ToUniversalTime().Ticks.ToString()} | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
    } finally { foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') } }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt 55) {
        $backend.Refresh()
        if ($backend.HasExited) { throw "Backend exited; see $runtime\backend*.log." }
        if ((Test-Port 8080) -and (Select-String -LiteralPath (Join-Path $runtime 'backend.log') -Pattern 'Started CivaServer' -Quiet)) {
            Write-Host 'Backend ready at http://127.0.0.1:8080/.'
            Write-Host 'Return to Android Studio and Run app. Register your own account on first use.'
            Write-Host 'Stop safely with: start-civa-local.bat -Stop (keeps all data).'
            exit 0
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Backend is still starting. See $runtime\backend.log before retrying."
} catch {
    Write-Host ('STARTUP FAILED: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
