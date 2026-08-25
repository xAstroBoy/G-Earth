param(
    [Parameter(Mandatory = $true)]
    [string] $NitroRendererFile,

    [string] $OutputDirectory = (Join-Path $PSScriptRoot '..\src\main\resources\build\common\messages'),

    [string[]] $HostNames = @('gh.b55.live', 'bsshotel.it'),

    [switch] $WriteSharedMap
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$resolvedRenderer = (Resolve-Path -LiteralPath $NitroRendererFile).Path
$source = Get-Content -Raw -LiteralPath $resolvedRenderer

function Convert-JavaScriptInteger([string] $literal) {
    if ($literal.StartsWith('0x', [System.StringComparison]::OrdinalIgnoreCase)) {
        return [Convert]::ToInt32($literal.Substring(2), 16)
    }

    $number = [double]::Parse(
        $literal,
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture
    )
    if ($number -ne [Math]::Truncate($number) -or $number -lt [int]::MinValue -or $number -gt [int]::MaxValue) {
        throw "Header value '$literal' is not a signed 32-bit integer."
    }
    return [int]$number
}

function Convert-PacketName([string] $constantName) {
    return (($constantName.Split('_') | ForEach-Object {
        if ($_.Length -eq 0) { return '' }
        $_.Substring(0, 1).ToUpperInvariant() + $_.Substring(1).ToLowerInvariant()
    }) -join '')
}

function Find-HeaderTable(
    [string] $anchorName,
    [int] $searchStart,
    [int] $searchEnd
) {
    $anchorPattern = [regex]::new(
        "(?<![A-Za-z0-9_`$])(?<symbol>[A-Za-z_`$][A-Za-z0-9_`$]*)\.$anchorName\s*=",
        [System.Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    $anchor = $anchorPattern.Match($source, $searchStart, $searchEnd - $searchStart)
    if (-not $anchor.Success) {
        throw "Could not find Nitro header anchor '$anchorName'."
    }

    $symbol = $anchor.Groups['symbol'].Value
    $classMarker = "const $symbol = class $symbol {"
    $tableStart = $source.LastIndexOf(
        $classMarker,
        $anchor.Index,
        [System.StringComparison]::Ordinal
    )
    if ($tableStart -lt $searchStart) {
        throw "Could not find the class table for Nitro symbol '$symbol'."
    }

    return [pscustomobject]@{
        Symbol = $symbol
        Start = $tableStart
    }
}

function Read-HeaderConstants(
    [string] $symbol,
    [int] $start,
    [int] $end
) {
    $block = $source.Substring($start, $end - $start)
    $escapedSymbol = [regex]::Escape($symbol)
    $numberPattern = '-?(?:0x[0-9a-f]+|\d+(?:\.\d+)?(?:e[+-]?\d+)?)'
    $pattern = [regex]::new(
        "(?<![A-Za-z0-9_`$])$escapedSymbol\.(?<name>[A-Z][A-Z0-9_]*)\s*=\s*(?<value>$numberPattern)\s*[,;]",
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
            [System.Text.RegularExpressions.RegexOptions]::CultureInvariant
    )

    $rows = [System.Collections.Generic.List[object]]::new()
    $byName = @{}
    foreach ($match in $pattern.Matches($block)) {
        $name = $match.Groups['name'].Value
        if ($byName.ContainsKey($name)) {
            throw "Duplicate Nitro header constant '$name'."
        }

        $row = [pscustomobject]@{
            ConstantName = $name
            Id = Convert-JavaScriptInteger $match.Groups['value'].Value
            Order = $rows.Count
        }
        $rows.Add($row)
        $byName[$name] = $row
    }

    if ($rows.Count -eq 0) {
        throw "No Nitro header constants were found for symbol '$symbol'."
    }

    return [pscustomobject]@{
        Rows = $rows
        ByName = $byName
    }
}

function Read-Registrations(
    [string] $block,
    [string] $mapName,
    [hashtable] $constantsByName
) {
    $pattern = [regex]::new(
        "this\._$mapName\.set\((?<key>[^,]+),\s*(?<handler>[^)]+)\)",
        [System.Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    $rows = [System.Collections.Generic.List[object]]::new()

    foreach ($match in $pattern.Matches($block)) {
        $key = $match.Groups['key'].Value.Trim()
        $handler = $match.Groups['handler'].Value.Trim()
        $constantName = $null
        $id = $null
        $isLiteral = $false

        if ($key -match '\.([A-Z][A-Z0-9_]*)$') {
            $constantName = $Matches[1]
            if (-not $constantsByName.ContainsKey($constantName)) {
                throw "Registered Nitro key '$key' has no declared header constant."
            }
            $id = $constantsByName[$constantName].Id
        } elseif ($key -match '^-?(?:0x[0-9a-f]+|\d+(?:\.\d+)?(?:e[+-]?\d+)?)$') {
            $id = Convert-JavaScriptInteger $key
            $isLiteral = $true
        } else {
            throw "Unsupported Nitro registration key '$key'."
        }

        $rows.Add([pscustomobject]@{
            ConstantName = $constantName
            Id = $id
            Handler = $handler
            IsLiteral = $isLiteral
            Order = $rows.Count
        })
    }

    if ($rows.Count -eq 0) {
        throw "No Nitro '$mapName' registrations were found."
    }
    return $rows
}

function Convert-ToMessageRows(
    [System.Collections.Generic.List[object]] $constants,
    [System.Collections.Generic.List[object]] $registrations
) {
    $rowsById = [ordered]@{}

    # Registered names are canonical because these are the classes Nitro will
    # actually instantiate. Multiple names can intentionally share one header;
    # keep the first registered name as the unambiguous logger label.
    foreach ($registration in $registrations) {
        $idKey = [string]$registration.Id
        if ($registration.Id -lt 0 -or $rowsById.Contains($idKey)) { continue }

        $constantName = $registration.ConstantName
        if ($registration.IsLiteral) {
            $sameHandler = $registrations | Where-Object {
                -not $_.IsLiteral -and $_.Handler -eq $registration.Handler
            } | Select-Object -First 1
            $constantName = if ($null -ne $sameHandler) {
                $sameHandler.ConstantName + '_LEGACY'
            } else {
                'HEADER_' + $registration.Id
            }
        }

        $rowsById[$idKey] = [ordered]@{
            Id = $registration.Id
            Name = Convert-PacketName $constantName
        }
    }

    # Nitro declares a small set of dormant/feature-gated headers that are not
    # registered in this build. Include them too so hidden UI paths still get a
    # real name if the server emits one.
    foreach ($constant in $constants) {
        $idKey = [string]$constant.Id
        if ($constant.Id -lt 0 -or $rowsById.Contains($idKey)) { continue }
        $rowsById[$idKey] = [ordered]@{
            Id = $constant.Id
            Name = Convert-PacketName $constant.ConstantName
        }
    }

    return @($rowsById.Values | Sort-Object Id)
}

function Assert-Header([object[]] $rows, [int] $id, [string] $name, [string] $direction) {
    $actual = $rows | Where-Object Id -eq $id | Select-Object -First 1
    if ($null -eq $actual -or $actual.Name -ne $name) {
        $actualName = if ($null -eq $actual) { '<missing>' } else { $actual.Name }
        throw "Critical $direction header $id resolved as '$actualName', expected '$name'."
    }
}

function Assert-Coverage(
    [System.Collections.Generic.List[object]] $constants,
    [System.Collections.Generic.List[object]] $registrations,
    [object[]] $rows,
    [string] $direction
) {
    $expected = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($constant in $constants) {
        if ($constant.Id -ge 0) { [void]$expected.Add($constant.Id) }
    }
    foreach ($registration in $registrations) {
        if ($registration.Id -ge 0) { [void]$expected.Add($registration.Id) }
    }

    $actual = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($row in $rows) { [void]$actual.Add($row.Id) }
    $missing = @($expected | Where-Object { -not $actual.Contains($_) })
    if ($missing.Count -gt 0 -or $actual.Count -ne $expected.Count) {
        throw "Incomplete $direction Nitro map. Missing IDs: $($missing -join ', ')."
    }
}

$eventsMethod = $source.IndexOf('registerEvents() {', [System.StringComparison]::Ordinal)
$composersMethod = $source.IndexOf('registerComposers() {', [System.StringComparison]::Ordinal)
$eventsGetter = $source.IndexOf('get events() {', $composersMethod, [System.StringComparison]::Ordinal)
if ($eventsMethod -lt 0 -or $composersMethod -le $eventsMethod -or $eventsGetter -le $composersMethod) {
    throw 'Could not locate Nitro message registration methods.'
}

$incomingTable = Find-HeaderTable 'AUTHENTICATED' 0 $eventsMethod
$outgoingTable = Find-HeaderTable 'SECURITY_TICKET' $incomingTable.Start $eventsMethod
if ($outgoingTable.Start -le $incomingTable.Start) {
    throw 'Nitro outgoing header table was not found after the incoming table.'
}

$incomingConstants = Read-HeaderConstants $incomingTable.Symbol $incomingTable.Start $outgoingTable.Start
$outgoingConstants = Read-HeaderConstants $outgoingTable.Symbol $outgoingTable.Start $eventsMethod
$incomingRegistrations = Read-Registrations `
    $source.Substring($eventsMethod, $composersMethod - $eventsMethod) `
    'events' `
    $incomingConstants.ByName
$outgoingRegistrations = Read-Registrations `
    $source.Substring($composersMethod, $eventsGetter - $composersMethod) `
    'composers' `
    $outgoingConstants.ByName

$incoming = Convert-ToMessageRows $incomingConstants.Rows $incomingRegistrations
$outgoing = Convert-ToMessageRows $outgoingConstants.Rows $outgoingRegistrations

Assert-Coverage $incomingConstants.Rows $incomingRegistrations $incoming 'incoming'
Assert-Coverage $outgoingConstants.Rows $outgoingRegistrations $outgoing 'outgoing'
Assert-Header $incoming 41 'Authenticated' 'incoming'
Assert-Header $incoming 1272 'ClientPing' 'incoming'
Assert-Header $outgoing 1461 'SecurityTicket' 'outgoing'
Assert-Header $outgoing 3294 'ClientPong' 'outgoing'
Assert-Header $outgoing 4000 'ReleaseVersion' 'outgoing'

$payload = [ordered]@{
    Incoming = $incoming
    Outgoing = $outgoing
} | ConvertTo-Json -Depth 4

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

foreach ($hostName in $HostNames) {
    $sanitizedHost = $hostName.Trim().ToLowerInvariant() -replace '[^a-z0-9._-]', '_'
    if ([string]::IsNullOrWhiteSpace($sanitizedHost)) {
        throw "Invalid empty host name '$hostName'."
    }
    $output = Join-Path $resolvedOutput "$sanitizedHost.json"
    [System.IO.File]::WriteAllText($output, $payload + [Environment]::NewLine, $utf8NoBom)
    Write-Host "Wrote $output"
}

if ($WriteSharedMap) {
    $sharedOutput = Join-Path (Split-Path -Parent $resolvedOutput) 'messages.json'
    [System.IO.File]::WriteAllText($sharedOutput, $payload + [Environment]::NewLine, $utf8NoBom)
    Write-Host "Wrote $sharedOutput"
}

Write-Host ((
    "Nitro coverage: {0} incoming constants / {1} registrations -> {2} named IDs; " +
    "{3} outgoing constants / {4} registrations -> {5} named IDs.") -f
    $incomingConstants.Rows.Count,
    $incomingRegistrations.Count,
    $incoming.Count,
    $outgoingConstants.Rows.Count,
    $outgoingRegistrations.Count,
    $outgoing.Count
)
