<#
run-soak.ps1
Installs OpenJDK and Apache Maven via winget (if available), updates PATH in this session,
then runs the SoakHealthCheckTest: mvn -Dtest=SoakHealthCheckTest -Dsoak.enabled=true test

Usage: Open PowerShell as Administrator and run:
    .\run-soak.ps1
#>

function Command-Exists {
    param([string]$Name)
    return (Get-Command $Name -ErrorAction SilentlyContinue) -ne $null
}

Write-Host "Checking Java and Maven..."
$haveJava = Command-Exists java
$haveMvn = Command-Exists mvn

if ($haveJava) { Write-Host "Java detected:"; java -version }
if ($haveMvn)  { Write-Host "Maven detected:"; mvn -v }

$useWinget = (Get-Command winget -ErrorAction SilentlyContinue) -ne $null

if (-not $useWinget) {
    Write-Host "winget not found. The script will only check and run if Java and Maven are already installed." -ForegroundColor Yellow
} else {
    if (-not $haveJava) {
        Write-Host "Attempting to install OpenJDK (Temurin 17) via winget..."
        $jdkIds = @('Eclipse.Adoptium.Temurin.17','Eclipse.Adoptium.Temurin.11','Microsoft.OpenJDK.17','Amazon.Corretto.17')
        $installed = $false
        foreach ($id in $jdkIds) {
            Write-Host "Trying winget install --id $id -e"
            winget install --id $id -e --accept-package-agreements --accept-source-agreements
            if ($LASTEXITCODE -eq 0) { $installed = $true; break }
        }
        if (-not $installed) { Write-Host "Failed to install a JDK via winget." -ForegroundColor Red }
    }

    if (-not $haveMvn) {
        Write-Host "Attempting to install Apache Maven via winget..."
        winget install --id Apache.Maven -e --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -ne 0) { Write-Host "winget install Apache.Maven failed." -ForegroundColor Yellow }
    }

    # reload PATH from Machine + User so this session can see newly-installed binaries
    $machinePath = [Environment]::GetEnvironmentVariable('Path','Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path','User')
    if ($machinePath) { $env:Path = $machinePath + ';' + $userPath }
}

Write-Host "Verifying tools..."
if (-not (Command-Exists java)) { Write-Host "Java not found. Install Java and re-run the script." -ForegroundColor Red; exit 2 }
if (-not (Command-Exists mvn))  { Write-Host "Maven not found. Install Maven and re-run the script." -ForegroundColor Red; exit 3 }

Write-Host "Java and Maven available. Printing versions..."
java -version
mvn -v

Write-Host "Running soak test: SoakHealthCheckTest"
$mvnArgs = '-Dtest=SoakHealthCheckTest','-Dsoak.enabled=true','test'
$mvnCmd = "mvn $($mvnArgs -join ' ')"
Write-Host "Executing: $mvnCmd"
& mvn @mvnArgs
$exit = $LASTEXITCODE
Write-Host "Maven exited with code $exit"
exit $exit
