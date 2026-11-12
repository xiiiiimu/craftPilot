$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:CraftPilotServices = @('postgres', 'redis', 'minio')

function Assert-CraftPilotDocker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker CLI is unavailable. Install Docker Desktop and open it manually.'
    }

    # Bind mounts must target this Windows machine, never a remote Docker daemon.
    $endpoint = $env:DOCKER_HOST
    if ($env:DOCKER_CONTEXT -or -not $endpoint) {
        $endpoint = docker context inspect --format '{{.Endpoints.docker.Host}}'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the active Docker context.' }
    }
    if ($endpoint -notmatch '^npipe:/{2,}\./pipe/') {
        throw 'Select a local Windows Docker Desktop context before running CraftPilot.'
    }
    $engine = docker info --format '{{.OSType}}' 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Engine is unavailable. Open Docker Desktop manually, then retry.'
    }
    if ($engine -ne 'linux') {
        throw 'Switch Docker Desktop to Linux containers manually, then retry.'
    }
}

function Assert-CraftPilotOwnership {
    $ids = @(docker ps -aq --filter 'label=com.docker.compose.project=craftpilot')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot list CraftPilot containers.' }
    foreach ($id in $ids) {
        $ownerPath = docker inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' $id
        if ($LASTEXITCODE -ne 0 -or $ownerPath -ne $script:ProjectRoot) {
            throw 'The Docker project name craftpilot is already used outside this workspace.'
        }
    }

    $networks = @(docker network ls --filter 'name=^craftpilot_dev$' --format '{{.ID}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot list CraftPilot networks.' }
    foreach ($id in $networks) {
        $owner = docker network inspect --format '{{index .Labels "com.docker.compose.project"}}/{{index .Labels "com.docker.compose.network"}}' $id
        if ($LASTEXITCODE -ne 0 -or $owner -ne 'craftpilot/dev') {
            throw 'The network craftpilot_dev already exists and is not owned by CraftPilot Compose.'
        }
    }
}

function Invoke-CraftPilotCompose {
    $envFile = Join-Path $script:ProjectRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        throw 'Create .env from .env.example and set the three development passwords.'
    }
    $composeFile = Join-Path $script:ProjectRoot 'compose.yaml'
    docker compose --project-name craftpilot --project-directory $script:ProjectRoot --env-file $envFile -f $composeFile @args
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose exited with code $LASTEXITCODE." }
}


function Assert-CraftPilotConfigurationMatches {
    $hashes = @{}
    foreach ($line in (Invoke-CraftPilotCompose config --hash '*')) {
        $service, $hash = $line -split '\s+', 2
        $hashes[$service] = $hash
    }

    foreach ($id in (Invoke-CraftPilotCompose ps --all --quiet @CraftPilotServices)) {
        $metadata = docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}} {{index .Config.Labels "com.docker.compose.config-hash"}} {{.HostConfig.RestartPolicy.Name}}' $id
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the existing CraftPilot configuration.' }
        $service, $hash, $restart = $metadata -split '\s+'
        if ($hashes[$service] -ne $hash -or $restart -ne 'no') {
            throw "Existing CraftPilot service '$service' differs from the Compose configuration. Review it manually; no container was replaced."
        }
    }
}
