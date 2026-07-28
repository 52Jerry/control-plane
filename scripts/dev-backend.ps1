$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env.local'

if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
        $parts = $_.Split('=', 2)
        if ($parts.Length -eq 2) {
            Set-Item -Path "Env:$($parts[0].Trim())" -Value $parts[1].Trim()
        }
    }
}

Push-Location (Join-Path $root 'backend')
try {
    mvn spring-boot:run
} finally {
    Pop-Location
}

