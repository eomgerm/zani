<#
.SYNOPSIS
Starts the local MySQL and Redis services and verifies the backend against them.

.DESCRIPTION
Validates Docker Compose, starts the local dependency containers, waits for
their health checks, and runs the backend test suite so Flyway and Hibernate
schema validation execute against MySQL. Existing local data volumes are kept.

.PARAMETER TimeoutSeconds
Maximum time to wait for each dependency to become healthy.

.PARAMETER SkipApplicationVerification
Starts and checks MySQL and Redis without running the backend test suite.

.EXAMPLE
.\backend\scripts\bootstrap.ps1

.EXAMPLE
.\backend\scripts\bootstrap.ps1 -SkipApplicationVerification
#>
[CmdletBinding()]
param(
    [ValidateRange(10, 600)]
    [int]$TimeoutSeconds = 180,

    [switch]$SkipApplicationVerification
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$backendRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $backendRoot "infra\compose\local.yml"

function Set-DefaultEnvironmentVariable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    $currentValue = [Environment]::GetEnvironmentVariable($Name, "Process")
    if ($null -eq $currentValue -or $currentValue.Length -eq 0) {
        [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
    }
}

function Invoke-ExternalCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Executable $($Arguments -join ' ')"
    }
}

function Get-ComposeContainerId {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Service
    )

    $output = & docker compose --file $composeFile ps --quiet $Service
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to resolve the container ID for $Service."
    }

    return ($output | Out-String).Trim()
}

function Wait-ForHealthyService {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Service,

        [Parameter(Mandatory = $true)]
        [int]$Timeout
    )

    $deadline = (Get-Date).AddSeconds($Timeout)
    do {
        $containerId = Get-ComposeContainerId -Service $Service
        if ($containerId.Length -gt 0) {
            $statusOutput = & docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $containerId
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to inspect the $Service container."
            }

            $status = ($statusOutput | Out-String).Trim()
            if ($status -eq "healthy") {
                Write-Host "[zani-bootstrap] $Service is healthy."
                return
            }
            if ($status -eq "unhealthy" -or $status -eq "exited" -or $status -eq "dead") {
                throw "$Service entered the '$status' state."
            }
        }

        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "Timed out after $Timeout seconds while waiting for $Service to become healthy."
}

function Test-WindowsPlatform {
    return $env:OS -eq "Windows_NT"
}

try {
    if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
        throw "Compose file not found: $composeFile"
    }

    if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker is not installed or is not available on PATH."
    }

    Invoke-ExternalCommand -Executable "docker" -Arguments @("version", "--format", "{{.Server.Version}}")
    Invoke-ExternalCommand -Executable "docker" -Arguments @("compose", "version", "--short")

    Set-DefaultEnvironmentVariable -Name "LOCAL_DB_NAME" -Value "zani"
    Set-DefaultEnvironmentVariable -Name "LOCAL_DB_PORT" -Value "3306"
    Set-DefaultEnvironmentVariable -Name "LOCAL_DB_USERNAME" -Value "root"
    Set-DefaultEnvironmentVariable -Name "LOCAL_DB_PASSWORD" -Value "root"
    Set-DefaultEnvironmentVariable -Name "LOCAL_REDIS_HOST" -Value "localhost"
    Set-DefaultEnvironmentVariable -Name "LOCAL_REDIS_PORT" -Value "6379"
    Set-DefaultEnvironmentVariable -Name "LOCAL_REDIS_PASSWORD" -Value ""

    if ($env:LOCAL_DB_USERNAME -ne "root") {
        throw "The local Compose environment currently supports LOCAL_DB_USERNAME=root only."
    }
    if (-not [string]::IsNullOrEmpty($env:LOCAL_REDIS_PASSWORD)) {
        throw "The local Compose environment uses Redis without authentication; LOCAL_REDIS_PASSWORD must be empty."
    }

    [Environment]::SetEnvironmentVariable(
        "LOCAL_DB_URL",
        "jdbc:mysql://localhost:$($env:LOCAL_DB_PORT)/$($env:LOCAL_DB_NAME)?serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "Process"
    )
    [Environment]::SetEnvironmentVariable("LOCAL_REDIS_HOST", "localhost", "Process")

    Write-Host "[zani-bootstrap] Validating local Compose configuration."
    Invoke-ExternalCommand -Executable "docker" -Arguments @("compose", "--file", $composeFile, "config", "--quiet")

    Write-Host "[zani-bootstrap] Starting MySQL and Redis."
    Invoke-ExternalCommand -Executable "docker" -Arguments @("compose", "--file", $composeFile, "up", "--detach")

    Wait-ForHealthyService -Service "mysql" -Timeout $TimeoutSeconds
    Wait-ForHealthyService -Service "redis" -Timeout $TimeoutSeconds

    if (-not $SkipApplicationVerification) {
        Write-Host "[zani-bootstrap] Running Flyway, Hibernate validation, and backend tests."
        Push-Location $backendRoot
        try {
            if (Test-WindowsPlatform) {
                Invoke-ExternalCommand -Executable ".\gradlew.bat" -Arguments @("test", "--rerun-tasks", "--no-daemon")
            }
            else {
                Invoke-ExternalCommand -Executable "bash" -Arguments @("./gradlew", "test", "--rerun-tasks", "--no-daemon")
            }
        }
        finally {
            Pop-Location
        }
    }

    Invoke-ExternalCommand -Executable "docker" -Arguments @("compose", "--file", $composeFile, "ps")
    Write-Host "[zani-bootstrap] Local MySQL and Redis are ready."
}
catch {
    [Console]::Error.WriteLine("[zani-bootstrap] $($_.Exception.Message)")
    if ($null -ne (Get-Command docker -ErrorAction SilentlyContinue) -and (Test-Path -LiteralPath $composeFile)) {
        & docker compose --file $composeFile ps
        & docker compose --file $composeFile logs --no-color --tail 100
    }
    exit 1
}
