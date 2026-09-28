$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskTomcat = 'E:/Program Files/Apache Software Foundation/Tomcat 11.0'
$taskOwnerFile = Join-Path $taskRoot 'data/tomcat-owned.pid'
$taskHealthUrl = 'http://127.0.0.1:8080/equipment-maintenance-demo/api/health'

try {
    if (-not (Test-Path -LiteralPath $taskOwnerFile -PathType Leaf)) {
        Write-Host 'This launcher did not start Tomcat; the existing Tomcat process was left running.'
        exit 0
    }
    $taskRecordedId = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $taskOwnerFile -Raw).Trim(), [ref]$taskRecordedId) -or $taskRecordedId -le 0) {
        throw 'Invalid launcher process record; no process was stopped.'
    }
    $taskListener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -eq $taskRecordedId }
    if (-not $taskListener) {
        Remove-Item -LiteralPath $taskOwnerFile -Force
        Write-Host 'The Tomcat process started by the launcher has already exited.'
        exit 0
    }
    $taskHealth = Invoke-RestMethod -Uri $taskHealthUrl -TimeoutSec 2
    if ($taskHealth.data.mode -ne 'demo' -or $taskHealth.data.runtime -ne 'java-servlet') {
        throw 'The process is not serving the expected Java demo; no process was stopped.'
    }

    $env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
    $env:CATALINA_HOME = $taskTomcat
    $env:CATALINA_BASE = $taskTomcat
    & (Join-Path $taskTomcat 'bin/catalina.bat') stop
    if ($LASTEXITCODE -ne 0) { throw 'Tomcat did not accept the shutdown request.' }
    for ($taskAttempt = 0; $taskAttempt -lt 30; $taskAttempt++) {
        $taskStillListening = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.OwningProcess -eq $taskRecordedId }
        if (-not $taskStillListening) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($taskStillListening) { throw 'Tomcat is still running; the process record was kept for another attempt.' }
    Remove-Item -LiteralPath $taskOwnerFile -Force
    Write-Host 'The Tomcat process started by the launcher has stopped.'
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
