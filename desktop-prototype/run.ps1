param([switch]$Check, [switch]$HostCheck, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$editorRoot = Split-Path $PSScriptRoot -Parent
$editorPom = Join-Path $PSScriptRoot 'pom.xml'
$editorMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($editorMaven) { $editorMavenPath = $editorMaven.Source }
else { $editorMavenPath = Join-Path $editorRoot 'research/tools/apache-maven-3.9.9/bin/mvn.cmd' }
if (-not $SkipBuild) {
    if (-not (Test-Path -LiteralPath $editorMavenPath)) { throw 'Install Maven 3.9+ and add mvn.cmd to PATH. See desktop-prototype/README.md.' }
    & $editorMavenPath -f $editorPom -q -B -ntp clean compile dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. See the compiler output above.' }
}
$editorJava = (Get-Command java -ErrorAction Stop).Source
$editorClasspath = (Join-Path $PSScriptRoot 'target/classes') + ';' + (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'target/classpath.txt') -Raw).Trim()
Push-Location $editorRoot
try {
    if ($Check) { & $editorJava -cp $editorClasspath editor.MainKt --check; if ($LASTEXITCODE -ne 0) { throw 'Kernel checks failed.' }; return }
    $editorArgs = @('-cp', ('"' + $editorClasspath + '"'), 'editor.MainKt')
    if ($HostCheck) { $editorArgs += @('--host-check','--diagnostics', ('"' + (Join-Path $PSScriptRoot 'target/ui-state.json') + '"')) }
    $editorProcess = Start-Process -FilePath $editorJava -ArgumentList $editorArgs -WorkingDirectory $editorRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $PSScriptRoot 'target/app-output.log') -RedirectStandardError (Join-Path $PSScriptRoot 'target/app-error.log') -PassThru
    $editorProcess.Id | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'target/app.pid')
    Write-Output 'Astra Editor is opening. Use Save (Ctrl+S) to keep your document.'
} finally { Pop-Location }
