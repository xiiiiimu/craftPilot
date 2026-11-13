#Requires -Version 5.1
<#
.SYNOPSIS
  R5 Windows one-click: bring up local infra + ensure backend jar exists.

.DESCRIPTION
  Idempotent bootstrap for CraftPilot R5 acceptance on Windows.
  Safety (hard rules):
  - Does NOT delete or wipe data/
  - Does NOT create, overwrite, or rewrite .env
  - Does NOT start the backend process (prints next steps instead)
  - Does NOT print secret values from .env

.EXAMPLE
  .\scripts\r5-one-click.ps1
#>
$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:JarPath = Join-Path $script:ProjectRoot 'backend/target/craftpilot-backend-0.1.0-SNAPSHOT.jar'
$script:EnvPath = Join-Path $script:ProjectRoot '.env'
$script:Mvnw = Join-Path $script:ProjectRoot 'backend/mvnw.cmd'
$script:DevUp = Join-Path $PSScriptRoot 'dev-up.ps1'

. "$PSScriptRoot/compose-common.ps1"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "  OK  $Message" -ForegroundColor Green
}

function Write-Fail([string]$Message) {
    Write-Host "  FAIL $Message" -ForegroundColor Red
}

function Read-DotEnvKeys([string]$Path) {
    $map = @{}
    foreach ($line in (Get-Content -LiteralPath $Path)) {
        if ($line -match '^\s*#' -or $line -match '^\s*$') { continue }
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            $map[$Matches[1]] = $Matches[2].Trim()
        }
    }
    return $map
}

function Test-KeyPresent([hashtable]$EnvMap, [string]$Name) {
    if (-not $EnvMap.ContainsKey($Name)) { return $false }
    $raw = $EnvMap[$Name]
    if ($null -eq $raw) { return $false }
    $trimmed = $raw.Trim().Trim('"').Trim("'")
    return -not [string]::IsNullOrWhiteSpace($trimmed)
}

function Test-TcpOpen([string]$HostName, [int]$Port, [int]$TimeoutMs = 2000) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
            return $false
        }
        $client.EndConnect($iar)
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-ComposeHealth([string]$Service) {
    try {
        # Prefer classic format: NAME STATUS (includes (healthy) when available).
        $lines = @(Invoke-CraftPilotCompose ps $Service)
        foreach ($line in $lines) {
            if ($line -match '\(healthy\)') { return 'healthy' }
            if ($line -match '\(unhealthy\)') { return 'unhealthy' }
            if ($line -match '\(health:\s*starting\)') { return 'starting' }
            if ($line -match '\bUp\b') { return 'running-no-health' }
        }
        # Fallback: JSON (Compose may emit NDJSON or an array).
        $raw = @(Invoke-CraftPilotCompose ps --format json $Service 2>$null)
        if (-not $raw -or ($raw.Count -eq 1 -and [string]::IsNullOrWhiteSpace($raw[0]))) {
            return 'missing'
        }
        $joined = ($raw -join "`n").Trim()
        if ($joined.StartsWith('[')) {
            $obj = $joined | ConvertFrom-Json
            if ($obj -is [System.Array]) {
                if ($obj.Count -eq 0) { return 'missing' }
                $obj = $obj[0]
            }
        } else {
            $obj = $raw[0] | ConvertFrom-Json
        }
        $health = $obj.Health
        if ([string]::IsNullOrWhiteSpace($health)) {
            $state = $obj.State
            if ($state -eq 'running') { return 'running-no-health' }
            if ($state) { return $state }
            return 'unknown'
        }
        return $health.ToString().ToLowerInvariant()
    } catch {
        return 'unknown'
    }
}

try {
    Write-Step 'R5 one-click (Windows) — safety preamble'
    Write-Host '  This script will NOT delete data/ and will NOT overwrite .env.'
    Write-Host '  Existing Compose containers are left in place when already healthy.'

    Write-Step 'Check Docker Desktop / local Linux engine'
    Assert-CraftPilotDocker
    Write-Ok 'Docker CLI and local Linux engine are available.'

    Write-Step 'Check .env presence (values never printed)'
    if (-not (Test-Path -LiteralPath $script:EnvPath -PathType Leaf)) {
        Write-Fail ".env is missing at $script:EnvPath"
        throw 'Create .env from .env.example and set development passwords. Refusing to create or overwrite .env.'
    }
    Write-Ok '.env file exists (not modified).'

    $envMap = Read-DotEnvKeys $script:EnvPath
    $required = @(
        'CRAFTPILOT_POSTGRES_DB',
        'CRAFTPILOT_POSTGRES_USER',
        'CRAFTPILOT_POSTGRES_ADMIN_PASSWORD',
        'CRAFTPILOT_POSTGRES_PASSWORD',
        'CRAFTPILOT_MINIO_ROOT_USER',
        'CRAFTPILOT_MINIO_ROOT_PASSWORD'
    )
    $optional = @(
        'CRAFTPILOT_POSTGRES_PORT',
        'CRAFTPILOT_REDIS_PORT',
        'CRAFTPILOT_MINIO_API_PORT',
        'CRAFTPILOT_MINIO_CONSOLE_PORT',
        'CRAFTPILOT_MINIO_BUCKET',
        'CRAFTPILOT_OPENAI_API_KEY',
        'CRAFTPILOT_QWEN_API_KEY',
        'CRAFTPILOT_CHAT_API_KEY'
    )

    $missingRequired = @()
    foreach ($name in $required) {
        if (Test-KeyPresent $envMap $name) {
            Write-Ok "$name = present"
        } else {
            Write-Fail "$name = absent/empty"
            $missingRequired += $name
        }
    }
    foreach ($name in $optional) {
        if (Test-KeyPresent $envMap $name) {
            Write-Ok "$name = present"
        } else {
            Write-Host "  --  $name = absent/empty (optional for infra up)" -ForegroundColor DarkYellow
        }
    }
    if ($missingRequired.Count -gt 0) {
        throw ("Required .env keys missing or empty: {0}. Fill them manually; this script does not write .env." -f ($missingRequired -join ', '))
    }

    $pgPort = 15432
    $redisPort = 17379
    $minioPort = 19000
    if (Test-KeyPresent $envMap 'CRAFTPILOT_POSTGRES_PORT') {
        $pgPort = [int]($envMap['CRAFTPILOT_POSTGRES_PORT'].Trim().Trim('"').Trim("'"))
    }
    if (Test-KeyPresent $envMap 'CRAFTPILOT_REDIS_PORT') {
        $redisPort = [int]($envMap['CRAFTPILOT_REDIS_PORT'].Trim().Trim('"').Trim("'"))
    }
    if (Test-KeyPresent $envMap 'CRAFTPILOT_MINIO_API_PORT') {
        $minioPort = [int]($envMap['CRAFTPILOT_MINIO_API_PORT'].Trim().Trim('"').Trim("'"))
    }

    Write-Step 'Start / refresh infra via scripts/dev-up.ps1'
    $alreadyUp = (Test-TcpOpen '127.0.0.1' $pgPort) -and (Test-TcpOpen '127.0.0.1' $redisPort) `
        -and (Test-TcpOpen '127.0.0.1' $minioPort)
    if ($alreadyUp) {
        Write-Ok ("Infra TCP already open on 127.0.0.1:{0}/{1}/{2}; skipping rebuild (no data wipe)." `
            -f $pgPort, $redisPort, $minioPort)
    } else {
        Assert-CraftPilotDocker
        Assert-CraftPilotOwnership
        & $script:DevUp
        if ($LASTEXITCODE -ne 0) {
            throw "scripts/dev-up.ps1 failed with exit code $LASTEXITCODE."
        }
        Write-Ok 'dev-up.ps1 completed.'
    }

    Write-Step 'Ensure backend jar (build only if missing)'
    if (Test-Path -LiteralPath $script:JarPath -PathType Leaf) {
        Write-Ok "Jar already present: backend/target/craftpilot-backend-0.1.0-SNAPSHOT.jar"
    } else {
        if (-not (Test-Path -LiteralPath $script:Mvnw -PathType Leaf)) {
            throw "Maven wrapper missing at $script:Mvnw"
        }
        Write-Host '  Building with backend/mvnw.cmd -B -ntp -DskipTests package ...'
        Push-Location (Join-Path $script:ProjectRoot 'backend')
        try {
            & .\mvnw.cmd -B -ntp -DskipTests package
            if ($LASTEXITCODE -ne 0) {
                throw "Maven package failed with exit code $LASTEXITCODE."
            }
        } finally {
            Pop-Location
        }
        if (-not (Test-Path -LiteralPath $script:JarPath -PathType Leaf)) {
            throw "Build finished but jar still missing: $script:JarPath"
        }
        Write-Ok 'Backend jar built.'
    }

    Write-Step 'Health-check postgres / redis / minio'
    $services = @(
        @{ Name = 'postgres'; Port = $pgPort },
        @{ Name = 'redis'; Port = $redisPort },
        @{ Name = 'minio'; Port = $minioPort }
    )
    $failed = @()
    foreach ($svc in $services) {
        $tcp = Test-TcpOpen '127.0.0.1' $svc.Port
        $health = 'tcp-only'
        try { $health = Get-ComposeHealth $svc.Name } catch { $health = 'compose-unavailable' }
        if ($tcp) {
            Write-Ok ("{0}: tcp=127.0.0.1:{1} open (compose={2})" -f $svc.Name, $svc.Port, $health)
        } else {
            Write-Fail ("{0}: tcp=127.0.0.1:{1} closed (compose={2})" -f $svc.Name, $svc.Port, $health)
            $failed += $svc.Name
        }
    }
    if ($failed.Count -gt 0) {
        Write-Host ''
        Write-Host 'Diagnostics:' -ForegroundColor Yellow
        try { Invoke-CraftPilotCompose ps --all @CraftPilotServices } catch { Write-Host $_.Exception.Message }
        throw ("Infra health check failed for: {0}" -f ($failed -join ', '))
    }

    Write-Step 'Next steps'
    Write-Host @'
  1) Start the backend (separate terminal; keeps running):
       .\scripts\backend-up.ps1
     Optional first-time demo owner bootstrap (login prefix r5-demo-*):
       $env:CRAFTPILOT_BOOTSTRAP_LOGIN = 'r5-demo-owner'
       $secure = Read-Host 'Development password' -AsSecureString
       $env:CRAFTPILOT_BOOTSTRAP_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
       try { .\scripts\backend-up.ps1 } finally {
         Remove-Item Env:CRAFTPILOT_BOOTSTRAP_LOGIN,Env:CRAFTPILOT_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
       }
     Health: http://127.0.0.1:18080/actuator/health/liveness
             http://127.0.0.1:18080/actuator/health/readiness

  2) V1 → V2 demo data bootstrap (after backend is up):
       .\scripts\r5-demo-v1-v2.ps1 -Login r5-demo-owner
     Or print-only steps:
       .\scripts\r5-demo-v1-v2.ps1 -PrintOnly

  3) Acceptance tests (Testcontainers; do not require this stack):
       cd backend
       .\mvnw.cmd -B -ntp -Dtest=R5EvaluationHarnessTest,R5MessageReplayTest,R5FaultInjectionTest,R5SecurityAcceptanceTest test

  4) Docs: docs/development/R5-windows-startup-demo.md
'@
    Write-Host ''
    Write-Ok 'R5 one-click finished successfully.'
    exit 0
} catch {
    Write-Host ''
    Write-Fail $_.Exception.Message
    Write-Host 'R5 one-click aborted. Fix the diagnostics above and re-run; the script is idempotent.' -ForegroundColor Yellow
    exit 1
}
