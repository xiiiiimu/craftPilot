. "$PSScriptRoot/compose-common.ps1"

Assert-CraftPilotDocker
Assert-CraftPilotOwnership
Invoke-CraftPilotCompose ps --all @CraftPilotServices


foreach ($id in (Invoke-CraftPilotCompose ps --all --quiet @CraftPilotServices)) {
    docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}} configured ports: {{json .HostConfig.PortBindings}}' $id
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the configured CraftPilot ports.' }
}
