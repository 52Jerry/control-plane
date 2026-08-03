[CmdletBinding()]
param(
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$envFile = Join-Path $root '.env.local'
$envTemplate = Join-Path $root '.env.local.example'
$jarFile = Join-Path $root 'backend\target\node-control-plane-0.1.0.jar'
$buildScript = Join-Path $root 'scripts\build.ps1'

function Import-LocalEnvironment {
    param([Parameter(Mandatory = $true)][string]$Path)

    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) {
            continue
        }

        $parts = $line.Split('=', 2)
        if ($parts.Length -ne 2) {
            throw "Invalid local configuration line: $rawLine"
        }

        $name = $parts[0].Trim()
        $value = $parts[1].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Assert-CommandAvailable {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. Install it and add it to PATH first."
    }
}

if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath $envTemplate -Destination $envFile
    throw '.env.local was created. Set a local password and encryption key, then run this script again.'
}

Import-LocalEnvironment -Path $envFile

$requiredVariables = @(
    'CONTROL_PLANE_DB_URL',
    'CONTROL_PLANE_DB_USERNAME',
    'CONTROL_PLANE_DB_PASSWORD',
    'CONTROL_PLANE_ENCRYPTION_KEY'
)
foreach ($variableName in $requiredVariables) {
    $value = (Get-Item -Path "Env:$variableName" -ErrorAction SilentlyContinue).Value
    if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith('replace-with-')) {
        throw "$variableName is not configured in .env.local."
    }
}

if ($env:CONTROL_PLANE_DB_URL -notmatch '^jdbc:mysql://[^/]+/control-plane(?:\?.*)?$') {
    throw 'The local launcher only accepts a MySQL URL whose database name is control-plane.'
}

Assert-CommandAvailable -Name 'java'

$sourcePaths = @(
    (Join-Path $root 'backend\pom.xml'),
    (Join-Path $root 'backend\src'),
    (Join-Path $root 'frontend\src'),
    (Join-Path $root 'frontend\package.json'),
    (Join-Path $root 'frontend\package-lock.json'),
    (Join-Path $root 'frontend\vite.config.js')
)
$latestSourceWrite = Get-ChildItem -LiteralPath $sourcePaths -Recurse -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1 -ExpandProperty LastWriteTimeUtc
$jarWrite = if (Test-Path -LiteralPath $jarFile) {
    (Get-Item -LiteralPath $jarFile).LastWriteTimeUtc
} else {
    [datetime]::MinValue
}

if ($Rebuild -or
    -not (Test-Path -LiteralPath $jarFile) -or
    $latestSourceWrite -gt $jarWrite) {
    Assert-CommandAvailable -Name 'mvn.cmd'
    Assert-CommandAvailable -Name 'npm.cmd'
    Write-Host 'The application needs to be built. Building frontend and backend...' -ForegroundColor Cyan
    & $buildScript
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jarFile)) {
        throw 'Build failed. Check the npm or Maven output above.'
    }
}

Write-Host ''
Write-Host 'Starting NiuSu Control Plane locally with Aliyun RDS' -ForegroundColor Green
Write-Host 'URL: http://127.0.0.1:8090'
Write-Host 'Login: use an enabled account stored in the Aliyun control_users table'
Write-Host 'Database: Aliyun RDS control-plane (schema changes and scheduled heartbeat writes are disabled)'
Write-Host 'Warning: manual changes made in the UI still update the online database' -ForegroundColor Yellow
Write-Host 'Press Ctrl+C to stop.'
Write-Host ''

Push-Location $root
try {
    java -jar $jarFile
    if ($LASTEXITCODE -ne 0) {
        throw "Control Plane exited with code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
