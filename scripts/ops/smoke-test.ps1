<#
.SYNOPSIS
    Post-deployment smoke test for a deployed Online Assignment Portal.

.DESCRIPTION
    Answers one question: is the release that was just deployed actually serving,
    and is it the release that was meant to be deployed?

    Five checks, and each exists because a different one of them can pass while
    the deployment is still wrong:

      1. /actuator/health answers a Spring health document reporting UP.
      2. /actuator/info reports the release version the pipeline published.
      3. /actuator/info reports the environment the pipeline targeted.
      4. /login renders, and renders a form with the email field in it.
      5. / redirects an anonymous visitor to /login.

    Why 2 and 3 are the important ones
        A deployment that pulled a stale image, or that the host was already
        running, passes 1, 4 and 5 perfectly — it is a healthy application, just
        not the one that was released. The version the application reports is
        baked into the image at build time from the same value as the image's tag
        (Dockerfile), so it cannot be satisfied by the host's configuration: it is
        the artefact identifying itself. Check 3 catches the subtler mistake of a
        release aimed at the wrong environment.

    Why 4 and 5, when the browser suite already proves the pages work
        They are not the same claim, and this script is not a second end-to-end
        suite — it is four HTTP requests, and the pyramid stays a pyramid
        (CLAUDE.md §6). The browser suite proves the application's behaviour on a
        test deployment the pipeline built itself. This proves that a template
        rendered and that the security filter chain answered, on the deployment
        that is now serving real users. If a deployment could show both healthy
        and blank, this is what would notice.

    What it deliberately does not check
        That the database has the right rows, that an upload works, or that a
        reviewer can approve. Those are the browser suite's job, and a smoke test
        that duplicated them would be a slower, weaker copy — and it would need
        credentials, which is the one thing a smoke test should never carry.

.PARAMETER BaseUrl
    Origin of the deployment, with no trailing slash. Such as http://portal.example.com:8080

.PARAMETER ExpectedVersion
    The release version, as published. The value of /actuator/info's app.version.

.PARAMETER ExpectedEnvironment
    staging or production. The value of /actuator/info's app.environment.

.PARAMETER TimeoutSeconds
    Per-request timeout.

.PARAMETER ReportPath
    Where to write the report. Written whether the test passes or fails.

.OUTPUTS
    Exit code 0 when every check passed, 1 otherwise.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [Parameter(Mandatory = $true)][string]$ExpectedVersion,
    [Parameter(Mandatory = $true)][string]$ExpectedEnvironment,
    [int]$TimeoutSeconds = 15,
    [string]$ReportPath = 'target\smoke-test.txt'
)

$ErrorActionPreference = 'Stop'

# Do not follow a redirect for the test that asserts one happens, and do not let
# a proxy or an error page be mistaken for the application.
$BaseUrl = $BaseUrl.TrimEnd('/')

$checks = New-Object System.Collections.Generic.List[string]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result {
    param([string]$Name, [bool]$Passed, [string]$Detail)
    $status = if ($Passed) { 'PASS' } else { 'FAIL' }
    $line = ('{0,-6} {1}: {2}' -f $status, $Name, $Detail)
    $checks.Add($line)
    if (-not $Passed) { $failures.Add($Name) }
    Write-Host ('[smoke-test] ' + $line)
}

function Get-WebResponse {
    param([string]$Uri, [int]$MaximumRedirection)
    # Returns a PSCustomObject with StatusCode and Content, for both the success
    # and the error path. Invoke-WebRequest throws on a non-2xx status, and on a
    # 3xx once redirection is capped, so the response has to be read off the
    # exception. There is one catch block that inspects the exception's response
    # rather than one per exception type: the type differs between Windows
    # PowerShell (WebException) and PowerShell 7 (HttpResponseException), and the
    # response object behind them is what this function actually wants.
    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec $TimeoutSeconds `
            -MaximumRedirection $MaximumRedirection
        return [PSCustomObject]@{
            StatusCode = [int]$response.StatusCode
            Content    = $response.Content
        }
    }
    catch {
        $failed = $_.Exception.Response
        if (-not $failed) { throw }
        $reader = New-Object System.IO.StreamReader($failed.GetResponseStream())
        return [PSCustomObject]@{
            StatusCode = [int]$failed.StatusCode
            Content    = $reader.ReadToEnd()
        }
    }
}

Write-Host "[smoke-test] Checking $BaseUrl for version $ExpectedVersion in $ExpectedEnvironment..."

# ---------------------------------------------------------------------------
# 1. Health
# ---------------------------------------------------------------------------
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec $TimeoutSeconds
    if ($health.status -eq 'UP') {
        Add-Result -Name 'health' -Passed $true -Detail 'the health endpoint reports UP'
    }
    else {
        Add-Result -Name 'health' -Passed $false -Detail ('the health endpoint reports {0}, not UP' -f $health.status)
    }
}
catch {
    Add-Result -Name 'health' -Passed $false -Detail ('the request failed: {0}' -f $_.Exception.Message)
}

# ---------------------------------------------------------------------------
# 2 and 3. The artefact identifies itself
# ---------------------------------------------------------------------------
try {
    $info = Invoke-RestMethod -Uri "$BaseUrl/actuator/info" -TimeoutSec $TimeoutSeconds

    $reportedVersion = $null
    $reportedEnvironment = $null
    if ($info -and $info.app) {
        $reportedVersion = $info.app.version
        $reportedEnvironment = $info.app.environment
    }

    if ($reportedVersion -eq $ExpectedVersion) {
        Add-Result -Name 'version' -Passed $true -Detail ("the application reports $reportedVersion")
    }
    else {
        Add-Result -Name 'version' -Passed $false -Detail (
            'the application reports ''{0}'', expected ''{1}''. The wrong image is running, or the endpoint is not exposed (application.yml: management.endpoints.web.exposure.include).' -f $reportedVersion, $ExpectedVersion)
    }

    if ($reportedEnvironment -eq $ExpectedEnvironment) {
        Add-Result -Name 'environment' -Passed $true -Detail ("the application reports $reportedEnvironment")
    }
    else {
        Add-Result -Name 'environment' -Passed $false -Detail (
            'the application reports ''{0}'', expected ''{1}''. A release may have been deployed to the wrong host.' -f $reportedEnvironment, $ExpectedEnvironment)
    }
}
catch {
    Add-Result -Name 'version' -Passed $false -Detail ('the request failed: {0}' -f $_.Exception.Message)
    Add-Result -Name 'environment' -Passed $false -Detail 'not checked; /actuator/info could not be read'
}

# ---------------------------------------------------------------------------
# 4. The sign-in page renders a sign-in form
# ---------------------------------------------------------------------------
try {
    $login = Get-WebResponse -Uri "$BaseUrl/login" -MaximumRedirection 5
    $marker = 'name=' + [char]34 + 'email' + [char]34
    if ($login.StatusCode -ne 200) {
        Add-Result -Name 'login-page' -Passed $false -Detail ("the sign-in page answered {0}, expected 200" -f $login.StatusCode)
    }
    elseif ($login.Content -notlike ('*' + $marker + '*')) {
        Add-Result -Name 'login-page' -Passed $false -Detail 'the sign-in page answered 200 but rendered no email field; the template did not render'
    }
    else {
        Add-Result -Name 'login-page' -Passed $true -Detail 'the sign-in page renders its form'
    }
}
catch {
    Add-Result -Name 'login-page' -Passed $false -Detail ('the request failed: {0}' -f $_.Exception.Message)
}

# ---------------------------------------------------------------------------
# 5. Anonymous access is redirected, so the security filter chain is live
# ---------------------------------------------------------------------------
try {
    $home = Get-WebResponse -Uri "$BaseUrl/" -MaximumRedirection 0
    # A redirect to /login means the filter chain refused an anonymous request,
    # which is the behaviour every security test asserts. A 200 here would mean
    # an unauthenticated visitor reached a page they should not have.
    if ($home.StatusCode -ge 300 -and $home.StatusCode -lt 400) {
        Add-Result -Name 'anonymous-redirect' -Passed $true -Detail ("an anonymous request for / answered {0}" -f $home.StatusCode)
    }
    else {
        Add-Result -Name 'anonymous-redirect' -Passed $false -Detail (
            'an anonymous request for / answered {0}, expected a redirect to the sign-in page. The security filter chain is not what the source says it is.' -f $home.StatusCode)
    }
}
catch {
    # Invoke-WebRequest throws when -MaximumRedirection 0 caps a redirect, and the
    # exception path above reads the 3xx off it. A throw that reaches here has no
    # response at all, which is a different failure and is reported as one.
    Add-Result -Name 'anonymous-redirect' -Passed $false -Detail (
        'the request failed rather than answering: {0}' -f $_.Exception.Message)
}

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
$outcome = if ($failures.Count -eq 0) { 'PASSED' } else { 'FAILED' }

$reportDirectory = Split-Path -Parent $ReportPath
if ($reportDirectory -and -not (Test-Path $reportDirectory)) {
    New-Item -ItemType Directory -Path $reportDirectory | Out-Null
}

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('SMOKE TEST ' + $outcome)
$lines.Add('==============')
$lines.Add('base url    : ' + $BaseUrl)
$lines.Add('version     : ' + $ExpectedVersion)
$lines.Add('environment : ' + $ExpectedEnvironment)
$lines.Add('')
foreach ($check in $checks) { $lines.Add($check) }
if ($failures.Count -gt 0) {
    $lines.Add('')
    $lines.Add('failed checks: ' + ($failures -join ', '))
}

Set-Content -Path $ReportPath -Value $lines -Encoding UTF8

if ($failures.Count -eq 0) {
    Write-Host '[smoke-test] All checks passed.'
    exit 0
}

Write-Host ('[smoke-test] FAILED: ' + ($failures -join ', '))
Write-Host "[smoke-test] The deployment is answering but is not what was released. See $ReportPath."
exit 1
