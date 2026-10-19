param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$repoName = 'shayann07/WYSIWYG-Text-Editor-Compose'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

function Invoke-GhJson([string[]]$Arguments) {
    $response = & gh @Arguments
    if ($LASTEXITCODE -ne 0) { throw "GitHub API command failed: $($Arguments -join ' ')" }
    if ($response) { return ($response | ConvertFrom-Json) }
}
function Send-GhBody([string]$Method, [string]$Endpoint, $Body) {
    $temporary = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($temporary, ($Body | ConvertTo-Json -Depth 30), [System.Text.UTF8Encoding]::new($false))
        return Invoke-GhJson @('api', '--method', $Method, $Endpoint, '--input', $temporary)
    } finally { Remove-Item -LiteralPath $temporary }
}

$account = Invoke-GhJson @('api', 'user')
$repository = Invoke-GhJson @('api', "repos/$repoName")
if ($account.login -ne 'shayann07' -or $repository.owner.login -ne 'shayann07' -or -not $repository.permissions.admin) {
    throw 'Expected authenticated repository owner shayann07 with admin access.'
}
$definitions = @('main-protection.json', 'main-history-safety.json') | ForEach-Object {
    Get-Content -Raw -LiteralPath (Join-Path $repoRoot ".github/rulesets/$_") | ConvertFrom-Json
}
$primary = $definitions[0]
if ($primary.bypass_actors.Count -ne 1 -or $primary.bypass_actors[0].actor_type -ne 'User' -or $primary.bypass_actors[0].actor_id -ne $account.id) {
    throw 'The direct-push bypass must name only the exact owner account.'
}
if ($definitions[1].bypass_actors.Count -ne 0) { throw 'History safety must have no bypass.' }
if (-not $Apply) {
    Write-Output 'Read-only preflight passed. -Apply requires a successful final verification receipt and pushed main.'
    $definitions | ConvertTo-Json -Depth 30
    exit 0
}

$receiptFile = Join-Path $repoRoot 'tooling/local/final-verification.json'
if (-not (Test-Path -LiteralPath $receiptFile)) { throw 'Final verification receipt is missing.' }
$receipt = Get-Content -Raw -LiteralPath $receiptFile | ConvertFrom-Json
if ($receipt.status -ne 'PASS' -or $receipt.gates.Count -lt 6 -or @($receipt.gates | Where-Object { $_.exitCode -ne 0 }).Count) {
    throw 'Every required local verification gate must pass before hardening activation.'
}
$requiredGates = @('docs-and-frozen-hashes', 'v6-witnesses', 'gradle-check-api', 'verification-contract', 'workspace-milestone', 'implementation-mutations')
foreach ($gate in $requiredGates) {
    if (@($receipt.gates | Where-Object { $_.name -eq $gate -and $_.exitCode -eq 0 }).Count -ne 1) {
        throw "Missing unique successful final gate: $gate"
    }
}
if (& git -C $repoRoot status --porcelain) { throw 'Commit all reviewed changes before governance activation.' }
$head = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $receipt.commit) { throw 'Verification receipt must identify the final commit.' }
$remote = Invoke-GhJson @('api', "repos/$repoName/commits/main")
if ($remote.sha -ne $head) { throw 'Push the verified commit to main before activation.' }

$settings = @{
    allow_squash_merge = $true; allow_merge_commit = $false; allow_rebase_merge = $false
    delete_branch_on_merge = $true; has_discussions = $true
    description = 'ComposeDoc: multilingual WYSIWYG document editing for Compose Multiplatform. Unicode, native input, layout and recovery; qualification status documented.'
    security_and_analysis = @{
        secret_scanning = @{ status = 'enabled' }
        secret_scanning_push_protection = @{ status = 'enabled' }
    }
}
$null = Send-GhBody 'PATCH' "repos/$repoName" $settings
$null = Send-GhBody 'PUT' "repos/$repoName/topics" @{ names = @('kotlin-multiplatform','compose-multiplatform','wysiwyg-editor','rich-text-editor','urdu','nastaliq','rtl','clean-architecture') }
foreach ($feature in @('vulnerability-alerts', 'automated-security-fixes', 'private-vulnerability-reporting')) {
    $null = Invoke-GhJson @('api', '--method', 'PUT', "repos/$repoName/$feature")
}
$existing = @(Invoke-GhJson @('api', "repos/$repoName/rulesets"))
foreach ($definition in $definitions) {
    $matches = @($existing | Where-Object { $_.name -eq $definition.name })
    if ($matches.Count -gt 1) { throw "Duplicate rulesets named $($definition.name); resolve explicitly." }
    if ($matches.Count -eq 1) {
        $result = Send-GhBody 'PUT' "repos/$repoName/rulesets/$($matches[0].id)" $definition
    } else {
        $result = Send-GhBody 'POST' "repos/$repoName/rulesets" $definition
    }
    $actual = Invoke-GhJson @('api', "repos/$repoName/rulesets/$($result.id)")
    if ($actual.enforcement -ne 'active') { throw "Ruleset not active: $($definition.name)" }
    if ($definition.name -eq 'main-owner-reviewed' -and ($actual.bypass_actors.Count -ne 1 -or $actual.bypass_actors[0].actor_type -ne 'User' -or $actual.bypass_actors[0].actor_id -ne $account.id)) {
        throw 'Owner-only bypass readback mismatch.'
    }
    if ($definition.name -eq 'main-history-safety' -and $actual.bypass_actors.Count -ne 0) { throw 'Unexpected safety bypass.' }
    if ($actual.target -ne 'branch' -or @($actual.conditions.ref_name.include).Count -ne 1 -or $actual.conditions.ref_name.include[0] -ne 'refs/heads/main') {
        throw 'Unexpected ruleset branch scope.'
    }
    foreach ($rule in $definition.rules) {
        $found = @($actual.rules | Where-Object { $_.type -eq $rule.type })
        if ($found.Count -ne 1) { throw "Missing unique rule: $($rule.type)" }
        if ($rule.type -eq 'required_status_checks') {
            if (-not $found[0].parameters.strict_required_status_checks_policy) { throw 'Strict status checking not enabled.' }
            $expectedChecks = @($rule.parameters.required_status_checks.context | Sort-Object)
            $actualChecks = @($found[0].parameters.required_status_checks.context | Sort-Object)
            if (Compare-Object $expectedChecks $actualChecks) { throw 'Required check contexts differ.' }
        }
        if ($rule.type -eq 'pull_request') {
            $policy = $found[0].parameters
            if ($policy.required_approving_review_count -ne 1 -or -not $policy.require_code_owner_review -or -not $policy.dismiss_stale_reviews_on_push -or -not $policy.required_review_thread_resolution) {
                throw 'PR review policy readback mismatch.'
            }
        }
    }
    Write-Output "Verified active ruleset $($actual.name), id $($actual.id)"
}
$readback = Invoke-GhJson @('api', "repos/$repoName")
if (-not $readback.allow_squash_merge -or $readback.allow_merge_commit -or $readback.allow_rebase_merge -or -not $readback.delete_branch_on_merge -or -not $readback.has_discussions) { throw 'Repository setting readback mismatch.' }
if ($readback.security_and_analysis.secret_scanning.status -ne 'enabled' -or $readback.security_and_analysis.secret_scanning_push_protection.status -ne 'enabled') { throw 'Secret scanning readback mismatch.' }
$null = Invoke-GhJson @('api', "repos/$repoName/vulnerability-alerts")
$securityFixes = Invoke-GhJson @('api', "repos/$repoName/automated-security-fixes")
if (-not $securityFixes.enabled) { throw 'Dependabot security updates readback mismatch.' }
Write-Output 'Governance activation complete. CodeRabbit app installation must be verified separately; YAML alone does not install it.'
