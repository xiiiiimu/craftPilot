. "$PSScriptRoot/compose-common.ps1"

Assert-CraftPilotDocker
Assert-CraftPilotOwnership
Invoke-CraftPilotCompose config --quiet
Assert-CraftPilotConfigurationMatches

$minioData = Join-Path $script:ProjectRoot 'data/minio'
if (-not (Test-Path -LiteralPath $minioData)) {
    New-Item -ItemType Directory -Path $minioData -Force | Out-Null
}
if (-not (Get-ChildItem -LiteralPath $minioData -Force | Select-Object -First 1)) {
    Invoke-CraftPilotCompose build minio
    Invoke-CraftPilotCompose run --rm --no-deps --user 0:0 --entrypoint /usr/local/bin/busybox minio chown 65532:65532 /data
}

try {
    Invoke-CraftPilotCompose up --detach --no-recreate --wait --wait-timeout 150 @CraftPilotServices
} finally {
    Invoke-CraftPilotCompose ps --all @CraftPilotServices
}
