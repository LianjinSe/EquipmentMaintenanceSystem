param([switch]$NoBrowser)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskEntry = Join-Path $taskRoot 'src/server.mjs'
$taskDataDir = Join-Path $taskRoot 'data'
$taskPidFile = Join-Path $taskDataDir 'demo.pid'
$taskUrl = 'http://127.0.0.1:4173'

function Test-DemoHealth {
    try {
        $taskHealth = Invoke-RestMethod -Uri "$taskUrl/api/health" -TimeoutSec 2
        return $taskHealth.data.mode -eq 'demo' -and $taskHealth.data.persistence -eq 'json-file'
    } catch {
        return $false
    }
}

try {
    $taskNode = Get-Command node -ErrorAction Stop
    $taskVersion = & $taskNode.Source --version
    $taskMajor = [int](($taskVersion -replace '^v', '') -split '\.')[0]
    if ($taskMajor -lt 22) {
        throw "演示需要 Node.js 22 或更新版本，当前为 $taskVersion。"
    }

    if (Test-DemoHealth) {
        Write-Host "演示服务已在 $taskUrl 运行。"
        if (-not $NoBrowser) { Start-Process -FilePath $taskUrl }
        exit 0
    }

    $taskPortOwner = Get-NetTCPConnection -LocalPort 4173 -State Listen -ErrorAction SilentlyContinue
    if ($taskPortOwner) {
        throw '4173 端口已被其他服务占用；未启动演示，避免打开错误页面。'
    }

    $taskServer = Start-Process -FilePath $taskNode.Source -ArgumentList ('"' + $taskEntry + '"') -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 40; $taskAttempt++) {
        if ($taskServer.HasExited) { break }
        if (Test-DemoHealth) {
            $taskReady = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $taskReady) {
        if (-not $taskServer.HasExited) { Stop-Process -Id $taskServer.Id -Force -ErrorAction SilentlyContinue }
        throw '演示服务未能启动。请在项目目录执行 npm start 查看错误。'
    }

    New-Item -ItemType Directory -Path $taskDataDir -Force | Out-Null
    Set-Content -LiteralPath $taskPidFile -Value $taskServer.Id -Encoding Ascii -NoNewline
    Write-Host "演示服务已启动：$taskUrl"
    if (-not $NoBrowser) { Start-Process -FilePath $taskUrl }
    Write-Host '完成后可双击“停止演示.cmd”关闭服务。'
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
