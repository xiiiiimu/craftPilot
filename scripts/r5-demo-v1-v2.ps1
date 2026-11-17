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
    [switch]$SkipBackendCheck,
    [switch]$UseProviders
)

$ErrorActionPreference = 'Stop'
$DemoLoginPrefix = 'r5-demo-'
$endpoint = [uri]$BaseUrl
if (-not $endpoint.IsAbsoluteUri -or $endpoint.Scheme -ne 'http' -or
    $endpoint.Host -notin @('localhost', '127.0.0.1', '[::1]') -or
    $endpoint.UserInfo -or $endpoint.AbsolutePath -ne '/' -or $endpoint.Query -or $endpoint.Fragment) {
    throw 'BaseUrl must be a local loopback HTTP origin.'
}
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
        MaximumRedirection = 0
        TimeoutSec = 60
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

function Upload-DemoAsset([hashtable]$Headers, [string]$MerchantId, [string]$Label) {
    # Distinct valid 64x64 PNGs satisfy the vision input size and revision comparison contracts.
    $images = @{
        v1 = 'iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAeElEQVR4nO3PQQkAMAzAwMqpfyZwIvY4BoEIuMzZ/brhgga0oAEtaEALGtCCBrSgAS1oQAsa0IIGtKABLWhACxrQgga0oAEtaEALGtCCBrSgAS1oQAsa0IIGtKABLWhACxrQgga0oAEtaEALGtCCBrSgAS1oQAseu+qJQPEh8FEUAAAAAElFTkSuQmCC'
        v2 = 'iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAeElEQVR4nO3PQQkAMAzAwMqpfyZwIvY4BoEIuMzu+brhgga0oAEtaEALGtCCBrSgAS1oQAsa0IIGtKABLWhACxrQgga0oAEtaEALGtCCBrSgAS1oQAsa0IIGtKABLWhACxrQgga0oAEtaEALGtCCBrSgAS1oQAseu2mKQPGSV3xbAAAAAElFTkSuQmCC'
    }
    $png = [Convert]::FromBase64String($images[$Label])

    $url = "$Api/merchants/$MerchantId/assets"
    Add-Type -AssemblyName System.Net.Http
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $form = [Net.Http.MultipartFormDataContent]::new()
    try {
        $client.DefaultRequestHeaders.Add('Authorization', $Headers.Authorization)
        $form.Add([Net.Http.StringContent]::new('image'), 'kind')
        $file = [Net.Http.ByteArrayContent]::new($png)
        $file.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('image/png')
        $form.Add($file, 'file', "$Label.png")
        $response = $client.PostAsync($url, $form).GetAwaiter().GetResult()
        try {
            [void]$response.EnsureSuccessStatusCode()
            return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).id
        } finally { $response.Dispose() }
    } finally {
        $form.Dispose()
        $client.Dispose()
    }
}

Write-Story

$modePrintOnly = [bool]$PrintOnly

if (-not $SkipBackendCheck) {
    Write-Step ("Probe backend liveness at {0}" -f $BaseUrl)
    try {
        $live = Invoke-WebRequest -Uri ("{0}/actuator/health/liveness" -f $BaseUrl) -UseBasicParsing -MaximumRedirection 0 -TimeoutSec 5
        if ($live.StatusCode -ge 200 -and $live.StatusCode -lt 300) {
            Write-Ok ("liveness HTTP {0}" -f $live.StatusCode)
        } else {
            throw ("Unexpected liveness status {0}" -f $live.StatusCode)
        }
    } catch {
        Write-Warn ("Backend not reachable: {0}" -f $_.Exception.Message)
        Write-Warn 'Start it with .\scripts\backend-up.ps1 after .\scripts\r5-one-click.ps1'
        if (-not $modePrintOnly) {
            throw 'Interactive demo requires a running local backend.'
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
if ($me.login -cne $Login) { throw 'Authenticated identity differs from the demo login.' }
Write-Ok ("principal login={0}" -f $me.login)
$merchants = Invoke-Json -Method GET -Url "$Api/me/merchants" -Headers $headers
if (@($merchants.items).Count -ne 1 -or $merchants.hasMore -or
    $merchants.items[0].role -ne 'OWNER' -or $merchants.items[0].name -ne 'Local development') {
    throw 'Use a dedicated owner created by the local development bootstrap.'
}
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
$messageV1 = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/groups/$($group.id)/messages" -Headers $headers -Body @{
    clientMsgId = [guid]::NewGuid().ToString()
    text        = 'r5-demo: like V1 fabric, want improvements'
    card        = @{ revisionId = $revisionV1.id }
}
Write-Ok ("surveyV1={0}" -f $surveyV1.id)

function Confirm-Feedback($Survey, $Revision, $Message) {
    $merchantApi = "$Api/merchants/$merchantId"
    $attribution = Invoke-Json -Method POST -Url "$merchantApi/messages/$($Message.id)/attribution" -Headers $headers
    if ($attribution.status -ne 'ATTRIBUTED' -or $attribution.revisionId -ne $Revision.id -or $attribution.surveyId -ne $Survey.id) {
        throw 'Feedback attribution did not match the survey and revision.'
    }
    $surveyApi = "$merchantApi/surveys/$($Survey.id)"
    $stats = Invoke-Json -Method GET -Url "$surveyApi/feedback-stats?revisionId=$($Revision.id)" -Headers $headers
    $issues = Invoke-Json -Method GET -Url "$surveyApi/issues?revisionId=$($Revision.id)" -Headers $headers
    if ($stats.attributedMessages -lt 1 -or @($issues).Count -lt 1) { throw 'Feedback statistics or issues are missing.' }
    Write-Ok "message=$($Message.id) attribution=$($attribution.id) survey=$($Survey.id) revision=$($Revision.id) issues=$(@($issues).Count)"
}

Write-Step 'Verify V1 feedback and issues'
Confirm-Feedback $surveyV1 $revisionV1 $messageV1
if ($UseProviders) {
    $search = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/surveys/$($surveyV1.id)/retrieval/search" -Headers $headers -Body @{
        revisionId = $revisionV1.id
        query = 'fabric'
        topK = 5
    }
    Write-Ok "retrievalMode=$($search.retrievalMode) hits=$(@($search.hits).Count)"
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
$closeHeaders = $headers.Clone()
$closeHeaders['Idempotency-Key'] = [guid]::NewGuid().ToString()
$closed = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/surveys/$($surveyV1.id)/close" -Headers $closeHeaders
if ($closed.status -eq 'CLOSING') {
    Write-Host "  Survey $($surveyV1.id) is CLOSING. Waiting for its documented closing window."
    $remaining = ([DateTimeOffset]$closed.closingDeadlineAt - [DateTimeOffset]::UtcNow).TotalSeconds
    if ($remaining -gt 660) { throw 'The closing deadline exceeds the demo wait limit.' }
    Start-Sleep -Seconds ([int][Math]::Max(1, [Math]::Ceiling($remaining) + 1))
    # A fresh command applies the due state transition. Reusing the key would replay CLOSING.
    $closeHeaders['Idempotency-Key'] = [guid]::NewGuid().ToString()
    $closed = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/surveys/$($surveyV1.id)/close" -Headers $closeHeaders
}
if ($closed.status -ne 'CLOSED') { throw "V1 survey must finish closing before opening V2 (status=$($closed.status), id=$($surveyV1.id))." }
Write-Ok "closed surveyV1=$($surveyV1.id)"
$surveyV2 = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/groups/$($group.id)/surveys" -Headers $headers -Body @{
    revisionIds = @($revisionV1.id, $revisionV2.id)
}
$messageV2 = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/groups/$($group.id)/messages" -Headers $headers -Body @{
    clientMsgId = [guid]::NewGuid().ToString()
    text        = 'r5-demo: V2 shoulder improved, higher purchase intent'
    card        = @{ revisionId = $revisionV2.id }
}
Confirm-Feedback $surveyV2 $revisionV2 $messageV2
$savedV1 = Invoke-Json -Method GET -Url "$Api/merchants/$merchantId/revisions/$($revisionV1.id)" -Headers $headers
$savedSurvey = Invoke-Json -Method GET -Url "$Api/merchants/$merchantId/surveys/$($surveyV1.id)" -Headers $headers
if ($savedV1.snapshotHash -ne $revisionV1.snapshotHash -or $revisionV2.parentRevisionId -ne $savedV1.id -or $savedSurvey.status -ne 'CLOSED') {
    throw 'V1 history or V2 parent linkage did not match the demo.'
}
Write-Ok ("revisionV2={0} surveyV2={1}" -f $revisionV2.id, $surveyV2.id)

if ($UseProviders) {
    Write-Step 'Vision compare V1 vs V2'
    $cmp = Invoke-Json -Method POST -Url "$Api/merchants/$merchantId/vision/compare" -Headers $headers -Body @{
        leftRevisionId  = $revisionV1.id
        rightRevisionId = $revisionV2.id
        surveyId        = $surveyV2.id
    }
    if ($cmp.status -ne 'SUCCEEDED') { throw "Vision comparison did not succeed (status=$($cmp.status))." }
    Write-Ok ("vision compare id={0}" -f $cmp.id)
} else {
    Write-Warn 'Provider probes require explicit -UseProviders and configured credentials.'
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
