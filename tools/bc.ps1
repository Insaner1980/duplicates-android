#Requires -Version 7.0

$ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$AndroidCheckRoot = if ([string]::IsNullOrWhiteSpace($env:ANDROID_CHECK_HOME)) {
    Join-Path $PSScriptRoot '../../Android-check'
} else {
    $env:ANDROID_CHECK_HOME
}
$EntryPoint = Join-Path $AndroidCheckRoot 'tools/InvokeProjectCheck.ps1'
if (-not (Test-Path -LiteralPath $EntryPoint -PathType Leaf)) {
    Write-Error 'ERROR/2: Android-check is missing. Set ANDROID_CHECK_HOME to its installation directory.' -ErrorAction Continue
    exit 2
}

$global:LASTEXITCODE = 2
& $EntryPoint -ProjectCheckCommand 'build-check' -Root $ProjectRoot -ProjectId 'duplicates-android' @args
exit $LASTEXITCODE
