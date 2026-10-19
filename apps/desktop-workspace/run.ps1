param([switch]$Check, [switch]$HostCheck, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
if ($SkipBuild) { throw 'Unidentified stale builds are not supported. Use the identity-checked verification runner for reuse.' }
$launcher = Join-Path $PSScriptRoot '../../tooling/launch.py'
if ($Check) { & python $launcher --check }
elseif ($HostCheck) { & python $launcher --host-check }
else { & python $launcher --app }
exit $LASTEXITCODE
