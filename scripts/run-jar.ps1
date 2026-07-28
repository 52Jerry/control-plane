$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env.local'
$jarFile = Join-Path $root 'backend\target\node-control-plane-0.1.0.jar'

if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
        $parts = $_.Split('=', 2)
        if ($parts.Length -eq 2) {
            Set-Item -Path "Env:$($parts[0].Trim())" -Value $parts[1].Trim()
        }
    }
}

if (-not (Test-Path $jarFile)) {
    throw "未找到 $jarFile，请先运行 .\scripts\build.ps1"
}

Push-Location $root
try {
    java -jar $jarFile
} finally {
    Pop-Location
}

