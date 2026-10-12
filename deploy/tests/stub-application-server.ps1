<#
.SYNOPSIS
    A stub of the deployed application, for exercising the release verification
    scripts without a real deployment.

.DESCRIPTION
    Serves the endpoints scripts/ci/wait-for-health.cmd and
    scripts/ops/smoke-test.ps1 ask about, and nothing else:

      GET /actuator/health  -> {"status":"UP"}
      GET /actuator/info    -> {"app":{"version":<running version>,"environment":<...>}}
      GET /login            -> 200, an HTML sign-in form carrying a CSRF field
      GET /                 -> 302 to /login
      GET /submissions      -> 302 to /login, unless the running version is one
                               this stub has been told to serve unguarded

    This is NOT the application and is not a substitute for one. It exists so that
    the two verification scripts can be run against something that answers the way
    a healthy deployment answers, which makes the verification itself — not the
    application — the thing under test. The application's own behaviour is proven
    by the unit, integration and Selenium suites, which is where CLAUDE.md §6 puts
    it.

    The version it reports is read from a file on every request, not from a
    constant, so a harness can change it between two requests and watch the smoke
    test notice. That is the property a deployment relies on: the version is the
    artefact identifying itself, not something the host configures.

    /submissions is the exception, and it is the point of the whole fixture. When
    the running version is listed in UnguardedVersions the route answers 200 to an
    anonymous caller, which is a real regression — an authorization boundary that
    is not in place — and one that /actuator/health reports as a perfectly healthy
    deployment. It is what the release model's "simulated failure" is: not a
    container that fails to start, but a release that starts and is wrong, which is
    the failure a health check alone cannot catch and a business-level smoke test
    exists to catch.

    Written on a raw TcpListener rather than System.Net.HttpListener, which needs a
    URL reservation (or elevation) before it will bind a prefix on Windows. A test
    fixture that only works in an elevated shell is a fixture that stops being run.

.PARAMETER Port
    The port to listen on. Bound to 127.0.0.1 only — this must never be reachable
    off the machine running the tests.

.PARAMETER VersionFile
    File holding the version to report from /actuator/info. Re-read per request.

.PARAMETER Environment
    The environment to report from /actuator/info.

.PARAMETER UnguardedVersions
    Versions for which /submissions answers 200 instead of redirecting.

.PARAMETER StopAfter
    Seconds after which the server exits on its own, so a harness that fails
    part-way cannot leave a listener behind.
#>
param(
    [int]$Port = 18080,
    [Parameter(Mandatory = $true)][string]$VersionFile,
    [string]$Environment = 'production',
    [string]$UnguardedVersions = '',
    [int]$StopAfter = 600
)

$ErrorActionPreference = 'Stop'

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()

$deadline = (Get-Date).AddSeconds($StopAfter)
$unguarded = @($UnguardedVersions.Split(',') | Where-Object { $_ -ne '' })

function Get-RunningVersion {
    if (Test-Path $VersionFile) { return (Get-Content $VersionFile -Raw).Trim() }
    return 'none'
}

function Format-Response {
    param([int]$Status, [string]$Reason, [string]$ContentType, [string]$Body, [string]$Location)

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $head = 'HTTP/1.1 ' + $Status + ' ' + $Reason + "`r`n"
    if ($Location) { $head += 'Location: ' + $Location + "`r`n" }
    $head += 'Content-Type: ' + $ContentType + "`r`n"
    $head += 'Content-Length: ' + $bytes.Length + "`r`n"
    # Connection: close, so each request is one socket and no keep-alive state has
    # to be tracked. Invoke-WebRequest reopens; this is a fixture, not a server.
    $head += "Connection: close`r`n`r`n"

    $headBytes = [System.Text.Encoding]::ASCII.GetBytes($head)
    $out = New-Object byte[] ($headBytes.Length + $bytes.Length)
    [Array]::Copy($headBytes, 0, $out, 0, $headBytes.Length)
    [Array]::Copy($bytes, 0, $out, $headBytes.Length, $bytes.Length)
    return $out
}

while ((Get-Date) -lt $deadline) {
    $client = $listener.AcceptTcpClient()
    try {
        $stream = $client.GetStream()
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII)
        $requestLine = $reader.ReadLine()
        # Drain the headers. Nothing is read from the body: every route here is a GET.
        while ($true) {
            $line = $reader.ReadLine()
            if ([string]::IsNullOrEmpty($line)) { break }
        }

        $path = '/'
        if ($requestLine -and $requestLine.Contains(' ')) {
            $parts = $requestLine.Split(' ')
            if ($parts.Length -ge 2) {
                $path = $parts[1]
                if ($path.Contains('?')) { $path = $path.Substring(0, $path.IndexOf('?')) }
            }
        }

        $version = Get-RunningVersion
        $bytes = $null

        switch ($path) {
            '/actuator/health' {
                $bytes = Format-Response -Status 200 -Reason 'OK' -ContentType 'application/json' -Body '{"status":"UP"}'
            }
            '/actuator/info' {
                $body = '{"app":{"version":"' + $version + '","environment":"' + $Environment + '"}}'
                $bytes = Format-Response -Status 200 -Reason 'OK' -ContentType 'application/json' -Body $body
            }
            '/login' {
                $body = '<html><body><form method="post" action="/login">' +
                        '<input type="hidden" name="_csrf" value="stub-token"/>' +
                        '<input type="text" name="email"/><input type="password" name="password"/>' +
                        '</form></body></html>'
                $bytes = Format-Response -Status 200 -Reason 'OK' -ContentType 'text/html;charset=UTF-8' -Body $body
            }
            '/submissions' {
                if ($unguarded -contains $version) {
                    # The simulated regression: the route is deployed, answers, and
                    # does not guard anything.
                    $bytes = Format-Response -Status 200 -Reason 'OK' -ContentType 'text/html;charset=UTF-8' -Body '<html><body>submissions</body></html>'
                }
                else {
                    $bytes = Format-Response -Status 302 -Reason 'Found' -ContentType 'text/html;charset=UTF-8' -Body '' -Location '/login'
                }
            }
            '/' {
                $bytes = Format-Response -Status 302 -Reason 'Found' -ContentType 'text/html;charset=UTF-8' -Body '' -Location '/login'
            }
            default {
                $bytes = Format-Response -Status 404 -Reason 'Not Found' -ContentType 'application/json' -Body '{}'
            }
        }

        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
    }
    catch {
        # A client that hangs up mid-request is not this fixture's problem, and a
        # fixture that dies on one is a fixture that makes a green run flaky.
    }
    finally {
        $client.Close()
    }
}

$listener.Stop()
