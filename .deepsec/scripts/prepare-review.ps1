$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$receiptPath = Join-Path $PSScriptRoot '../data/duplicates-android/source-scan.json'
$configPath = Join-Path $PSScriptRoot '../data/duplicates-android/config.json'
$infoPath = Join-Path $PSScriptRoot '../data/duplicates-android/INFO.md'
$sonarCommand = Get-Command sonar.exe -CommandType Application -ErrorAction SilentlyContinue
$sonarPath = if ($sonarCommand) { $sonarCommand.Source } else { Join-Path $env:LOCALAPPDATA 'sonarqube-cli/bin/sonar.exe' }
if (-not (Test-Path -LiteralPath $sonarPath -PathType Leaf)) { throw 'Sonar secrets scanner is unavailable.' }

function Invoke-SecretsScan {
    param([string[]]$Paths)
    $scanOutput = & $sonarPath analyze secrets @Paths 2>&1
    $scanExitCode = $LASTEXITCODE
    $scanText = $scanOutput -join "`n"
    Write-Output $scanText
    if ($scanExitCode -ne 0 -or $scanText -notmatch 'No secrets found' -or
        $scanText -match 'Path not found|Scan warning|Scan error|ERROR|❌') {
        throw 'Required pre-read secrets scan did not complete cleanly. AI processing is blocked.'
    }
}

$relativePaths = @('app/src', 'app/schemas', 'app/build.gradle.kts', 'config', '.github', 'gradle', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', '.gitignore', 'README.md', 'PROJECT.md')
if (Test-Path -LiteralPath (Join-Path $projectRoot 'AGENTS.md')) { $relativePaths += 'AGENTS.md' }
$scanPaths = @($relativePaths | ForEach-Object { Join-Path $projectRoot $_ })
$scanPaths += $configPath
Invoke-SecretsScan -Paths $scanPaths
$files = foreach ($scanPath in $scanPaths) {
    if (Test-Path -LiteralPath $scanPath -PathType Container) {
        Get-ChildItem -LiteralPath $scanPath -File -Recurse -Force
    } else {
        Get-Item -LiteralPath $scanPath
    }
}
$hashes = [ordered]@{}
foreach ($file in $files | Sort-Object FullName -Unique) {
    if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Source link is outside the verified read scope: $($file.FullName)" }
    $relative = [IO.Path]::GetRelativePath($projectRoot, $file.FullName).Replace('\', '/')
    $hashes[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$receipt = [ordered]@{
    scanner = 'sonar analyze secrets'
    completedAt = [DateTimeOffset]::UtcNow.ToString('o')
    files = $hashes
}
[IO.File]::WriteAllText($receiptPath, ($receipt | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
$projectConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
# Revalidation reads INFO.md rather than the process-only promptAppend setting.
[IO.File]::WriteAllText($infoPath, $projectConfig.promptAppend, [Text.UTF8Encoding]::new($false))
Invoke-SecretsScan -Paths @($receiptPath, $infoPath)
Write-Output "Pre-read secrets scan verified $($hashes.Count) files; receipt: .deepsec/data/duplicates-android/source-scan.json"
