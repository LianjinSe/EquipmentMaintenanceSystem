$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskPidFile = Join-Path $taskRoot 'data/demo.pid'
$taskUrl = 'http://127.0.0.1:4173/api/health'

try {
    if (-not (Test-Path -LiteralPath $taskPidFile -PathType Leaf)) {
        Write-Host '没有通过“启动演示.cmd”启动的服务。'
        exit 0
    }

    $taskRecordedId = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $taskPidFile -Raw).Trim(), [ref]$taskRecordedId) -or $taskRecordedId -le 0) {
        throw '演示进程记录无效；未停止任何进程。'
    }

    $taskProcess = Get-Process -Id $taskRecordedId -ErrorAction SilentlyContinue
    if (-not $taskProcess) {
        Remove-Item -LiteralPath $taskPidFile -Force
        Write-Host '记录的演示进程已退出。'
        exit 0
    }
    if ($taskProcess.ProcessName -ne 'node') {
        throw '记录的进程 ID 已被其他程序使用；未停止任何进程。'
    }

    $taskListener = Get-NetTCPConnection -LocalPort 4173 -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -eq '127.0.0.1' -and $_.OwningProcess -eq $taskRecordedId }
    if (-not $taskListener) {
        throw '记录的进程未监听演示地址；未停止任何进程。'
    }
    $taskHealth = Invoke-RestMethod -Uri $taskUrl -TimeoutSec 2
    if ($taskHealth.data.mode -ne 'demo' -or $taskHealth.data.persistence -ne 'json-file') {
        throw '监听进程不是预期演示服务；未停止任何进程。'
    }

    Stop-Process -Id $taskRecordedId -Force
    Remove-Item -LiteralPath $taskPidFile -Force
    Write-Host '本地演示服务已停止。'
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
