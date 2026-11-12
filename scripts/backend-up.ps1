$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dotenv = Join-Path $root '.env'
$jar = Join-Path $root 'backend/target/craftpilot-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $dotenv)) { throw 'Create the project .env before starting the backend.' }
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend with backend/mvnw.cmd package first.' }

$settings = @{}
foreach ($line in (Get-Content -LiteralPath $dotenv)) {
    if ($line -match '^\s*(CRAFTPILOT_[A-Z_]+)\s*=(.*)$') {
        $settings[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}

function Resolve-LocalSetting([string]$name, [string]$fallback = '') {
    $processValue = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ($processValue) { return $processValue }
    if ($settings[$name]) { return $settings[$name] }
    if ($fallback) { return $fallback }
    throw "Set $name in the project .env."
}

$pgPort = Resolve-LocalSetting 'CRAFTPILOT_POSTGRES_PORT' '15432'
$database = Resolve-LocalSetting 'CRAFTPILOT_POSTGRES_DB'
$minioPort = Resolve-LocalSetting 'CRAFTPILOT_MINIO_API_PORT' '19000'
$minioAccessKey = if ($env:CRAFTPILOT_MINIO_ACCESS_KEY) { $env:CRAFTPILOT_MINIO_ACCESS_KEY } else {
    Resolve-LocalSetting 'CRAFTPILOT_MINIO_ROOT_USER'
}
$minioSecretKey = if ($env:CRAFTPILOT_MINIO_SECRET_KEY) { $env:CRAFTPILOT_MINIO_SECRET_KEY } else {
    Resolve-LocalSetting 'CRAFTPILOT_MINIO_ROOT_PASSWORD'
}
$backendSettings = @{
    SPRING_PROFILES_ACTIVE = 'dev'
    CRAFTPILOT_HTTP_ADDRESS = '127.0.0.1'
    CRAFTPILOT_DEV_AUTH_ENABLED = 'true'
    CRAFTPILOT_JDBC_URL = "jdbc:postgresql://127.0.0.1:${pgPort}/$database`?connectTimeout=3&socketTimeout=5"
    CRAFTPILOT_POSTGRES_USER = Resolve-LocalSetting 'CRAFTPILOT_POSTGRES_USER'
    CRAFTPILOT_POSTGRES_PASSWORD = Resolve-LocalSetting 'CRAFTPILOT_POSTGRES_PASSWORD'
    CRAFTPILOT_REDIS_PORT = Resolve-LocalSetting 'CRAFTPILOT_REDIS_PORT' '17379'
    CRAFTPILOT_MINIO_ENDPOINT = "http://127.0.0.1:$minioPort"
    CRAFTPILOT_MINIO_ACCESS_KEY = $minioAccessKey
    CRAFTPILOT_MINIO_SECRET_KEY = $minioSecretKey
    CRAFTPILOT_MINIO_BUCKET = Resolve-LocalSetting 'CRAFTPILOT_MINIO_BUCKET' 'craftpilot-r1-0-verification'
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$previous = @{}
try {
    foreach ($name in $backendSettings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $backendSettings[$name], 'Process')
    }
    & $java -jar $jar
    if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE." }
} finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
