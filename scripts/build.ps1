$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Push-Location (Join-Path $root 'frontend')
try {
    npm.cmd run build
} finally {
    Pop-Location
}

Push-Location (Join-Path $root 'backend')
try {
    mvn clean package
} finally {
    Pop-Location
}

