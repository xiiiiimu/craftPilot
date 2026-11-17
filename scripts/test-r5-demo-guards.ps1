#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$demo = Join-Path $PSScriptRoot 'r5-demo-v1-v2.ps1'
$powershell = (Get-Process -Id $PID).Path

function Assert-DemoExit([bool]$Success, [string]$Expected, [string[]]$DemoArgs) {
    # Capture the child's diagnostics without treating its expected stderr as a test failure.
    $ErrorActionPreference = 'Continue'
    $output = & $powershell -NoProfile -File $demo @DemoArgs 2>&1 | Out-String
    if (($LASTEXITCODE -eq 0) -ne $Success -or $output -notmatch [regex]::Escape($Expected)) {
        throw "Unexpected demo result for $($DemoArgs -join ' '): $output"
    }
}

Assert-DemoExit $true 'no API mutations' @('-PrintOnly', '-SkipBackendCheck')
Assert-DemoExit $false 'local loopback HTTP origin' @('-BaseUrl', 'https://example.invalid', '-SkipBackendCheck')

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
try {
    $port = $listener.LocalEndpoint.Port
    # Reserve the port without serving HTTP. The liveness probe must time out and exit nonzero.
    Assert-DemoExit $false 'requires a running local backend' @('-BaseUrl', "http://127.0.0.1:$port")
} finally {
    $listener.Stop()
}
Write-Output '3 R5 demo guard checks passed.'
