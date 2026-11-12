. "$PSScriptRoot/compose-common.ps1"

Assert-CraftPilotDocker
Assert-CraftPilotOwnership
Invoke-CraftPilotCompose stop @CraftPilotServices
Invoke-CraftPilotCompose ps --all @CraftPilotServices
