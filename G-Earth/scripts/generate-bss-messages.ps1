param(
    [Parameter(Mandatory = $true)]
    [string] $HeadersFile,

    [string] $OutputDirectory = (Join-Path $PSScriptRoot '..\src\main\resources\build\common\messages')
)

$ErrorActionPreference = 'Stop'

$resolvedHeaders = (Resolve-Path -LiteralPath $HeadersFile).Path
$headers = Get-Content -Raw -LiteralPath $resolvedHeaders

$incomingStart = $headers.IndexOf('public static class In', [System.StringComparison]::Ordinal)
$outgoingStart = $headers.IndexOf('public static class Out', [System.StringComparison]::Ordinal)
if ($incomingStart -lt 0 -or $outgoingStart -le $incomingStart) {
    throw "Could not find BssHeaders.In and BssHeaders.Out in '$resolvedHeaders'."
}

$constantPattern = [regex]'public const short\s+(?<name>[A-Z0-9_]+)\s*=\s*(?<id>-?\d+);'

function Convert-PacketName([string] $constantName) {
    return (($constantName.Split('_') | ForEach-Object {
        if ($_.Length -eq 0) { return '' }
        $_.Substring(0, 1).ToUpperInvariant() + $_.Substring(1).ToLowerInvariant()
    }) -join '')
}

function Convert-HeaderBlock([string] $block) {
    $seen = [System.Collections.Generic.HashSet[int]]::new()
    $rows = foreach ($match in $constantPattern.Matches($block)) {
        $id = [int]$match.Groups['id'].Value
        if ($id -lt 0 -or -not $seen.Add($id)) { continue }

        [ordered]@{
            Id = $id
            Name = Convert-PacketName $match.Groups['name'].Value
        }
    }

    return @($rows | Sort-Object Id)
}

$incoming = Convert-HeaderBlock $headers.Substring($incomingStart, $outgoingStart - $incomingStart)
$outgoing = Convert-HeaderBlock $headers.Substring($outgoingStart)

if ($incoming.Count -eq 0 -or $outgoing.Count -eq 0) {
    throw 'Refusing to write an empty BSS packet map.'
}

$payload = [ordered]@{
    Incoming = $incoming
    Outgoing = $outgoing
} | ConvertTo-Json -Depth 4

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

# G-Earth identifies this BSS connection by its websocket host. Keep the hotel-domain
# alias as well so manual/direct configurations resolve the same complete packet map.
foreach ($hostName in @('gh.b55.live', 'bsshotel.it')) {
    $output = Join-Path $resolvedOutput "$hostName.json"
    [System.IO.File]::WriteAllText($output, $payload + [Environment]::NewLine, $utf8NoBom)
    Write-Host "Wrote $output"
}

Write-Host "BSS packet names: $($incoming.Count) incoming, $($outgoing.Count) outgoing."
