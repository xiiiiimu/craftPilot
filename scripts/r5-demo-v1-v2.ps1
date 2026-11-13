#Requires -Version 5.1
<#
.SYNOPSIS
  R5 V1 to V2 demo bootstrap helpers for a local CraftPilot backend.

.DESCRIPTION
  Documents and optionally automates the R5 demo story:
    V1 sample -> survey -> messages/issues -> evidence/retrieval/vision ->
    (manual) merchant confirmation -> V2 revision -> new survey.

  Isolation: demo merchant logins MUST use prefix r5-demo-*.
  Safety: does NOT auto-approve HITL approvals or merchant production decisions.
  Does NOT delete data/ and does NOT overwrite .env.

.EXAMPLE
  .\scripts\r5-demo-v1-v2.ps1 -PrintOnly

.EXAMPLE
  .\scripts\r5-demo-v1-v2.ps1 -Login r5-demo-owner
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$Login = '',
    [string]$Password = '',
    [switch]$PrintOnly,
    [switch]$SkipBackendCheck
)

$ErrorActionPreference = 'Stop'
$DemoLoginPrefix = 'r5-demo-'
$Api = $BaseUrl.TrimEnd('/') + '/api/v1'

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host ("==> {0}" -f $Message) -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host ("  OK  {0}" -f $Message) -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host ("  !!  {0}" -f $Message) -ForegroundColor DarkYellow
}

function Write-Story {
    Write-Host 'R5 V1->V2 demo story:'
    Write-Host '  1. Campaign + style + V1 revision (assets + BOM)'
    Write-Host '  2. Open survey on V1; post card-attributed feedback'
    Write-Host '  3. Issues / feedback-stats / retrieval / vision'
    Write-Host '  4. Ticket/Run/HITL approval - MANUAL ONLY'
    Write-Host '  5. Merchant decision - MANUAL ONLY (no auto-approve)'
    Write-Host '  6. Create V2 (parentRevisionId=V1); new survey covering V1+V2'
    Write-Host '  7. Feedback on V2; V1 history remains readable'
    Write-Host 'Demo logins must start with r5-demo-*'
}

function New-BasicAuthHeader([string]$User, [string]$Pass) {
    $pair = '{0}:{1}' -f $User, $Pass
    $bytes = [Text.Encoding]::UTF8.GetBytes($pair)
    $token = [Convert]::ToBase64String($bytes)
    return @{ Authorization = "Basic $token"; 'Content-Type' = 'application/json' }
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Headers,
        [object]$Body = $null
    )
    $params = @{
        Method      = $Method
        Uri         = $Url
        Headers     = $Headers
        ErrorAction = 'Stop'
    }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
    }
    return Invoke-RestMethod @params
}

function Show-PrintOnlyGuide([string]$User) {
    $loginShown = if ($User) { $User } else { 'r5-demo-owner' }
    Write-Step 'Print-only API steps'
    Write-Host ("# login = {0}" -f $loginShown)
    Write-Host "# 1) GET $Api/me and $Api/me/merchants (Basic auth)"
    Write-Host "# 2) POST groups / campaigns / styles / revisions (V1)"
    Write-Host "# 3) POST surveys + group messages with card.revisionId"
    Write-Host "# 4) GET issues + feedback-stats; POST retrieval/search"
    Write-Host "# 5) MANUAL: approvals/decide and decisions/decide - never auto"
    Write-Host "# 6) POST V2 revision (parentRevisionId=V1) + new survey + vision/compare"
    Write-Warn 'PrintOnly: no API mutations; no approvals/decisions posted.'
}

function New-MinimalPngBytes {
    $b64 = 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAFElEQVQ4T2NkYGD4z0ABYBw1gGABAD8YAQf9fYQnAAAAAElFTkSuQmCC'
    return [Convert]::FromBase64String($b64)
}

function Upload-DemoAsset([hashtable]$Headers, [string]$MerchantId, [string]$Label) {
    $png = New-MinimalPngBytes
    $tmp = Join-Path $env:TEMP ("craftpilot-{0}-{1}.png" -f $Label, [guid]::NewGuid().ToString('N'))
    [IO.File]::WriteAllBytes($tmp, $png)
    try {
        $url = "$Api/merchants/$MerchantId/assets"
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if ($curl) {
            $out = & curl.exe -sS -f -X POST `
                -H ("Authorization: {0}" -f $Headers.Authorization) `
                -F ("file=@{0};type=image/png" -f $tmp) `
                $url
            if ($LASTEXITCODE -ne 0) {
                throw ("Asset upload via curl.exe failed with exit code {0}" -f $LASTEXITCODE)
            }
            $resp = $out | ConvertFrom-Json
            return $resp.id
        }
        if ($PSVersionTable.PSVersion.Major -ge 6) {
            $authOnly = @{ Authorization = $Headers.Authorization }
            $resp = Invoke-RestMethod -Method POST -Headers $authOnly -Uri $url -Form @{ file = Get-Item -LiteralPath $tmp }
            return $resp.id
        }
        throw 'Asset upload requires curl.exe or PowerShell 7+ (-Form).'
    } finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

Write-Story

$modePrintOnly = [bool]$PrintOnly

if (-not $SkipBackendCheck) {
    Write-Step ("Probe backend liveness at {0}" -f $BaseUrl)
    try {
        $live = Invoke-WebRequest -Uri ("{0}/actuator/health/liveness" -f $BaseUrl) -UseBasicParsing -TimeoutSec 5
        if ($live.StatusCode -ge 200 -and $live.StatusCode -lt 300) {
            Write-Ok ("liveness HTTP {0}" -f $live.StatusCode)
        } else {
            throw ("Unexpected liveness status {0}" -f $live.StatusCode)
        }
    } catch {
        Write-Warn ("Backend not reachable: {0}" -f $_.Exception.Message)
        Write-Warn 'Start it with .\scripts\backend-up.ps1 after .\scripts\r5-one-click.ps1'
        if (-not $modePrintOnly) {
            Write-Host 'Falling back to PrintOnly instructions.' -ForegroundColor Yellow
            $modePrintOnly = $true
        }
    }
}

if ($modePrintOnly) {
    Show-PrintOnlyGuide -User $Login
    Write-Host ''
    Write-Warn 'PrintOnly mode: no API mutations performed; no approvals/decisions posted.'
    exit 0
}

if (-not $Login) {
    $Login = $env:CRAFTPILOT_DEMO_LOGIN
}
if (-not $Password) {
    $Password = $env:CRAFTPILOT_DEMO_PASSWORD
}
if (-not $Login) {
    $Login = Read-Host 'Demo owner login (must start with r5-demo-)'
}
if (-not $Login.StartsWith($DemoLoginPrefix)) {
    throw ("Demo login must start with '{0}'. Got: {1}" -f $DemoLoginPrefix, $Login)
}
if (-not $Password) {
    $secure = Read-Host 'Demo owner password' -AsSecureString
    $Password = [Net.NetworkCredential]::new('', $secure).Password
}
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw 'Password is required for API bootstrap.'
}

$headers = New-BasicAuthHeader $Login $Password

Write-Step 'Authenticate and resolve merchant'
$me = Invoke-Json -Method GET -Url "$Api/me" -Headers $headers
Write-Ok ("principal login={0}" -f $me.login)
$merchants = Invoke-Json -Method GET -Url "$Api/me/merchants" -Headers $headers
$merchantId = $merchants.items[0].id
Write-Ok ("merchantId={0}" -f $merchantId)

Write-Step 'Create group / campaign / style / V1 revision'
$group = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/groups" -Headers $headers -Body @{ name = 'r5-demo-room' }
$campaign = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/campaigns" -Headers $headers -Body @{ name = 'r5-demo-campaign' }
$style = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/campaigns/$($campaign.id)/styles" -Headers $headers -Body @{
    name     = 'r5-demo-style'
    category = 'doll-clothes'
}
$assetV1 = Upload-DemoAsset -Headers $headers -MerchantId $merchantId -Label 'v1'
$revisionV1 = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/styles/$($style.id)/revisions" -Headers $headers -Body @{
    assetIds = @($assetV1)
    bomItems = @(@{
            lineNo       = 1
            materialCode = 'FAB-DEMO'
            materialName = 'Cotton'
            quantity     = 1
            unit         = 'm'
            costGap      = $true
        })
    notes    = 'r5-demo-v1'
}
Write-Ok ("revisionV1={0}" -f $revisionV1.id)

Write-Step 'Open V1 survey and post attributed feedback'
$surveyV1 = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/groups/$($group.id)/surveys" -Headers $headers -Body @{
    revisionIds = @($revisionV1.id)
}
Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/groups/$($group.id)/messages" -Headers $headers -Body @{
    clientMsgId = [guid]::NewGuid().ToString()
    text        = 'r5-demo: like V1 fabric, want improvements'
    card        = @{ revisionId = $revisionV1.id }
} | Out-Null
Write-Ok ("surveyV1={0}" -f $surveyV1.id)

Write-Step 'Read issues / feedback-stats / retrieval (best-effort)'
try {
    Invoke-Json -Method GET -Url ("$Api/merchants/$merchantId/surveys/$($surveyV1.id)/issues?revisionId={0}" -f $revisionV1.id) -Headers $headers | Out-Null
    Write-Ok 'issues OK'
} catch {
    Write-Warn ("issues: {0}" -f $_.Exception.Message)
}
try {
    Invoke-Json -Method GET -Url ("$Api/merchants/$merchantId/surveys/$($surveyV1.id)/feedback-stats?revisionId={0}" -f $revisionV1.id) -Headers $headers | Out-Null
    Write-Ok 'feedback-stats OK'
} catch {
    Write-Warn ("feedback-stats: {0}" -f $_.Exception.Message)
}
try {
    Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/surveys/$($surveyV1.id)/retrieval/search" -Headers $headers -Body @{
        revisionId = $revisionV1.id
        query      = 'fabric'
        topK       = 5
    } | Out-Null
    Write-Ok 'retrieval/search invoked'
} catch {
    Write-Warn ("retrieval/search: {0}" -f $_.Exception.Message)
}

Write-Step 'Create V2 revision and new survey (V1 history retained)'
$assetV2 = Upload-DemoAsset -Headers $headers -MerchantId $merchantId -Label 'v2'
$revisionV2 = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/styles/$($style.id)/revisions" -Headers $headers -Body @{
    parentRevisionId = $revisionV1.id
    assetIds         = @($assetV2)
    bomItems         = @(@{
            lineNo       = 1
            materialCode = 'FAB-DEMO'
            materialName = 'Cotton'
            quantity     = 1
            unit         = 'm'
            costGap      = $true
        })
    notes            = 'r5-demo-v2'
}
try {
    Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/surveys/$($surveyV1.id)/close" -Headers $headers -Body @{} | Out-Null
    Write-Ok ("closed surveyV1={0}" -f $surveyV1.id)
} catch {
    Write-Warn ("close surveyV1: {0}" -f $_.Exception.Message)
}
$surveyV2 = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/groups/$($group.id)/surveys" -Headers $headers -Body @{
    revisionIds = @($revisionV1.id, $revisionV2.id)
}
Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/groups/$($group.id)/messages" -Headers $headers -Body @{
    clientMsgId = [guid]::NewGuid().ToString()
    text        = 'r5-demo: V2 shoulder improved, higher purchase intent'
    card        = @{ revisionId = $revisionV2.id }
} | Out-Null
Write-Ok ("revisionV2={0} surveyV2={1}" -f $revisionV2.id, $surveyV2.id)

Write-Step 'Vision compare V1 vs V2 (best-effort; needs provider keys)'
try {
    $cmp = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/vision/compare" -Headers $headers -Body @{
        leftRevisionId  = $revisionV1.id
        rightRevisionId = $revisionV2.id
        surveyId        = $surveyV2.id
    }
    Write-Ok ("vision compare id={0}" -f $cmp.id)
} catch {
    Write-Warn ("vision/compare: {0}" -f $_.Exception.Message)
}

Write-Step 'Manual gates (NOT automated)'
Write-Warn 'This script does NOT call /runtime/approvals/*/decide or /runtime/decisions/*/decide.'
Write-Host ''
Write-Host 'Summary (demo ids):'
Write-Host ("  login      = {0}" -f $Login)
Write-Host ("  merchantId = {0}" -f $merchantId)
Write-Host ("  groupId    = {0}" -f $group.id)
Write-Host ("  styleId    = {0}" -f $style.id)
Write-Host ("  revisionV1 = {0}" -f $revisionV1.id)
Write-Host ("  surveyV1   = {0}" -f $surveyV1.id)
Write-Host ("  revisionV2 = {0}" -f $revisionV2.id)
Write-Host ("  surveyV2   = {0}" -f $surveyV2.id)
Write-Ok 'V1->V2 demo bootstrap finished (approvals/decisions left manual).'
exit 0
