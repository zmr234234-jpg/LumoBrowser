# Builds app/src/main/assets/adblock/rules.txt from two inputs:
#
#   tools/adblock/upstream-hosts.txt   a hosts-format snapshot of an upstream filter list
#   tools/adblock/supplemental.txt     project-maintained additions (Chinese ad networks,
#                                      path fragments, exceptions)
#
# Both are data; this script is the only thing that writes the asset, so the asset always
# carries the provenance header that says where its entries came from and how many there are.
#
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\build_adblock_rules.ps1
#
# The script is deliberately strict: it refuses to write an asset whose host-rule count is
# absurdly small (which is what a truncated or half-converted input looks like) and it prints
# the counts so the numbers in the docs can be checked against a real run.

param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Version = $null
)

$ErrorActionPreference = 'Stop'

$upstreamPath = Join-Path $Root 'tools/adblock/upstream-hosts.txt'
$supplementalPath = Join-Path $Root 'tools/adblock/supplemental.txt'
$assetPath = Join-Path $Root 'app/src/main/assets/adblock/rules.txt'

foreach ($p in @($upstreamPath, $supplementalPath)) {
    if (-not (Test-Path $p)) { throw "missing input: $p" }
}

function Read-RuleLines([string]$path) {
    $raw = Get-Content $path -Encoding UTF8
    $trimmed = $raw | ForEach-Object { $_.Trim() }
    return $trimmed | Where-Object { $_ -ne '' -and -not $_.StartsWith('#') }
}

function Convert-HostsLine([string]$line) {
    # hosts format: "0.0.0.0 example.com" / "127.0.0.1 example.com" -> "example.com"
    # Everything else is passed through untouched (plain host, ||host^, /path, @@host).
    if ($line -match '^(0\.0\.0\.0|127\.0\.0\.1)\s+(\S+)$') { return $Matches[2] }
    return $line
}

$upstream = Read-RuleLines $upstreamPath | ForEach-Object { Convert-HostsLine $_ }
$supplemental = Read-RuleLines $supplementalPath | ForEach-Object { Convert-HostsLine $_ }

# Supplemental wins on conflict: it is the file a human edits, and an exception there must be
# able to override an upstream blocking rule.
$seen = New-Object 'System.Collections.Generic.HashSet[string]'
$hosts = New-Object 'System.Collections.Generic.List[string]'
$paths = New-Object 'System.Collections.Generic.List[string]'
$exceptions = New-Object 'System.Collections.Generic.List[string]'

function Add-Rule([string]$rule, [string]$source) {
    $key = $rule.ToLowerInvariant()
    if (-not $seen.Add($key)) { return }
    if ($rule.StartsWith('@@')) { $exceptions.Add($rule) }
    elseif ($rule.StartsWith('/')) { $paths.Add($rule) }
    else { $hosts.Add($rule) }
}

foreach ($r in $upstream) { Add-Rule $r 'upstream' }
foreach ($r in $supplemental) { Add-Rule $r 'supplemental' }

if ($hosts.Count -lt 1000) {
    throw "refusing to write rules.txt: only $($hosts.Count) host rules (a truncated input looks exactly like this)"
}

$stamp = if ($Version) { $Version } else { (Get-Date -Format 'yyyy.MM.dd') }

$out = New-Object 'System.Collections.Generic.List[string]'
$out.Add('# LumoBrowser bundled filter rules')
$out.Add('#')
$out.Add("# version: $stamp")
$out.Add("# updated: $((Get-Date -Format 'yyyy-MM-dd'))")
$out.Add('# origin:  bundled with the APK (no network download in this build)')
$out.Add('#')
$out.Add('# SOURCES')
$out.Add('#   [1] Peter Lowe''s ad / tracker / malware server list (hosts format snapshot)')
$out.Add('#       https://pgl.yoyo.org/adservers/   author: Peter Lowe <pgl@yoyo.org>')
$out.Add('#       licence: CC BY-NC-SA 4.0          snapshot: 2026-10-11, entries: 3548')
$out.Add("#       entries contributed to this file: $($upstream.Count)")
$out.Add('#   [2] project supplement, hand-maintained: tools/adblock/supplemental.txt')
$out.Add('#       Chinese ad networks, Chinese analytics/tracking endpoints, path fragments,')
$out.Add('#       exceptions. See docs/AD_BLOCKING.md for what it does and does not cover.')
$out.Add("#       entries contributed to this file: $($supplemental.Count)")
$out.Add('#')
$out.Add("# counts: $($hosts.Count) hosts, $($paths.Count) paths, $($exceptions.Count) exceptions")
$out.Add('#')
$out.Add('# FORMAT')
$out.Add('#   domain        block this host and every subdomain of it')
$out.Add('#   @@domain      exception: never block (checked before every blocking rule)')
$out.Add('#   /fragment     block a third-party request whose path+query contains the fragment')
$out.Add('#                 (fragments shorter than 4 characters are dropped by the parser)')
$out.Add('#')
$out.Add('# SCOPE (enforced in code, not here)')
$out.Add('#   Only http/https subresource requests are candidates. The main document is never')
$out.Add('#   blocked, first-party requests are never matched against path fragments, and a')
$out.Add('#   request that fails any check is served from the network exactly as it would be')
$out.Add('#   with ad blocking switched off. This file cannot make a page blank.')
$out.Add('')
$out.AddRange($exceptions)
$out.AddRange($paths)
$out.AddRange($hosts)

Set-Content -Path $assetPath -Value $out -Encoding UTF8

$bytes = (Get-Item $assetPath).Length
"wrote $assetPath"
"  hosts       : $($hosts.Count)"
"  paths       : $($paths.Count)"
"  exceptions  : $($exceptions.Count)"
"  lines       : $($out.Count)"
"  bytes       : $bytes"
"  version     : $stamp"
