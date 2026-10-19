# 一键启动：学成在线门户 AI 助手（agent-service）
# 功能：检测端口/进程 → 未运行时启动 uvicorn（独立窗口）→ 自动打开浏览器
$ErrorActionPreference = "Stop"
# 禁用系统代理：确保本机探测（localhost:8000）不被代理拦截
[System.Net.WebRequest]::DefaultWebProxy = $null
$ProjDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Port = 8000
$Url = "http://localhost:$Port"
$PyExe = Join-Path $ProjDir "venv\Scripts\python.exe"

Write-Host ""
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  学成在线门户 AI 助手 · 一键启动" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

# 1) 检查虚拟环境
if (-not (Test-Path $PyExe)) {
    Write-Host "[错误] 未找到虚拟环境：$PyExe" -ForegroundColor Red
    Write-Host "请先创建虚拟环境并安装依赖（见 README.md）" -ForegroundColor Yellow
    Read-Host "按回车退出"
    exit 1
}

# 2) 检查端口是否已被本服务占用
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
$running = $false
if ($listener) {
    foreach ($c in $listener) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($c.OwningProcess)" -ErrorAction SilentlyContinue
        if ($proc -and $proc.CommandLine -match "uvicorn app\.main:app") {
            $running = $true
        }
    }
}

if ($running) {
    Write-Host "[提示] 服务已在运行，直接打开页面：" -ForegroundColor Green
    Write-Host "      $Url" -ForegroundColor Green
    Start-Process $Url
    exit 0
}

if ($listener) {
    Write-Host "[错误] 端口 $Port 被其他程序占用，无法启动。" -ForegroundColor Red
    Read-Host "按回车退出"
    exit 1
}

# 3) 启动服务（独立窗口，关闭窗口即停止服务）
Write-Host "[启动] 正在启动服务（独立窗口）..." -ForegroundColor Green
$logFile = Join-Path $ProjDir "data\server.log"
Start-Process -FilePath $PyExe `
    -ArgumentList "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "$Port" `
    -WorkingDirectory $ProjDir `
    -WindowStyle Normal

# 4) 等待服务就绪后打开浏览器（纯 TCP 探测，不走代理；最多约 42 秒）
Write-Host "[等待] 等待服务就绪..." -ForegroundColor Yellow
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 700
    $tcp = New-Object System.Net.Sockets.TcpClient
    try {
        $tcp.Connect("127.0.0.1", $Port)
        $ok = $true
        $tcp.Close()
        break
    } catch {
        $tcp.Close()
    }
    if (($i + 1) % 10 -eq 0) {
        Write-Host "[等待] 已等待 $([math]::Round(($i + 1) * 0.7)) 秒..." -ForegroundColor Yellow
    }
}
if ($ok) {
    Write-Host "[完成] 服务已就绪，正在打开页面：" -ForegroundColor Green
    Write-Host "      $Url" -ForegroundColor Green
    Start-Process $Url
} else {
    Write-Host "[警告] 30 秒内未检测到服务就绪，请查看服务窗口日志。" -ForegroundColor Yellow
    Write-Host "      手动打开：$Url" -ForegroundColor Yellow
}
