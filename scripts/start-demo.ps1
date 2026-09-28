param([switch]$NoBrowser)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskTomcat = 'E:/Program Files/Apache Software Foundation/Tomcat 11.0'
$taskMavenFallback = 'D:/Program Files/JetBrains/IntelliJ IDEA 2024.3.2.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd'
$taskUrl = 'http://127.0.0.1:8080/equipment-maintenance-demo/'
$taskWar = Join-Path $taskRoot 'target/equipment-maintenance-demo.war'
$taskDeployedWar = Join-Path $taskTomcat 'webapps/equipment-maintenance-demo.war'
$taskOwnerFile = Join-Path $taskRoot 'data/tomcat-owned.pid'

function Get-DemoHealth {
    try {
        $taskResult = Invoke-RestMethod -Uri ($taskUrl + 'api/health') -TimeoutSec 2
        if ($taskResult.data.mode -eq 'demo' -and $taskResult.data.runtime -eq 'java-servlet' -and $taskResult.data.version -eq '0.2.0') {
            return $taskResult
        }
    } catch {}
    return $null
}

try {
    if (-not (Test-Path -LiteralPath (Join-Path $taskTomcat 'bin/catalina.bat'))) {
        throw 'Tomcat 11 installation was not found at the configured location.'
    }
    $taskMavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    $taskMaven = if ($taskMavenCommand) { $taskMavenCommand.Source } else { $taskMavenFallback }
    if (-not (Test-Path -LiteralPath $taskMaven)) {
        throw 'Maven was not found. Open pom.xml in IntelliJ IDEA and run package from the Maven tool window.'
    }
    $env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
    $env:CATALINA_HOME = $taskTomcat
    $env:CATALINA_BASE = $taskTomcat

    Push-Location -LiteralPath $taskRoot
    try { & $taskMaven -q package } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $taskWar)) {
        throw 'Maven package failed. See the build output above.'
    }

    Copy-Item -LiteralPath $taskWar -Destination $taskDeployedWar -Force
    $taskExistingListener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    $taskStartedTomcat = -not [bool]$taskExistingListener
    if ($taskStartedTomcat) {
        Start-Process -FilePath (Join-Path $taskTomcat 'bin/catalina.bat') -ArgumentList 'run' -WorkingDirectory (Join-Path $taskTomcat 'bin') -WindowStyle Hidden | Out-Null
    }

    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
        if (Get-DemoHealth) {
            $taskReady = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $taskReady) { throw 'The Java demo did not become ready in Tomcat. Check Tomcat logs.' }

    if ($taskStartedTomcat) {
        $taskOwner = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction Stop | Select-Object -First 1
        New-Item -ItemType Directory -Path (Split-Path -Parent $taskOwnerFile) -Force | Out-Null
        Set-Content -LiteralPath $taskOwnerFile -Value $taskOwner.OwningProcess -Encoding Ascii -NoNewline
    }
    Write-Host ('Java demo is ready: ' + $taskUrl)
    if (-not $NoBrowser) { Start-Process -FilePath $taskUrl }
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
