# 停止：学成在线门户 AI 助手（agent-service）
# 仅结束命令行匹配 "uvicorn app.main:app" 的服务进程，不误伤其他 Python 程序
$ErrorActionPreference = "SilentlyContinue"

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  学成在线门户 AI 助手 · 停止服务" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

$targets = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -match "uvicorn app\.main:app" }

if (-not $targets) {
    Write-Host "[提示] 未检测到运行中的 AI 助手服务（可能已停止）。" -ForegroundColor Yellow
} else {
    foreach ($t in $targets) {
        Write-Host "[停止] 结束进程 PID=$($t.ProcessId)（$($t.CommandLine.Substring(0, [Math]::Min(80, $t.CommandLine.Length)))...）" -ForegroundColor Green
        Stop-Process -Id $t.ProcessId -Force
    }
    Write-Host "[完成] 服务已停止。" -ForegroundColor Green
}
