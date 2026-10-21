<#
.SYNOPSIS
    The receipt a whole-app walk leaves, and what makes one good enough to publish a bundle on.

.DESCRIPTION
    Dot-sourced by device-audit.ps1, which writes walk-receipt.json beside summary.txt at the end
    of every run, and by release/release.ps1, whose publish stage refuses a bundle without a
    receipt that backs it. Nothing goes out until an APK patched from the bundle being published
    has been walked end to end on a release phone or an emulator and came back PASS.

    The receipt doesn't name the bundle by a path somebody typed. It carries the patch build
    identity the patched app reported about itself during the walk (BundleIdentity's
    "sha256=...; source=clean:...; tree=...; inputs=..."), and publish works out the same string
    from the bundle it's about to upload. A walk of any other build can't stand in for this one.
#>

# The phones release walks run on, by ro.product.model. An emulator passes by its serial.
$WalkReceiptPhones = @('SM-S908U1', 'SM-S938B')

function New-WalkReceipt {
    <#
    .SYNOPSIS
        The receipt for one device-audit run, as an ordered dictionary ready for ConvertTo-Json.
    #>
    param(
        [Parameter(Mandatory = $true)][ValidateSet('PASS', 'PARTIAL', 'FAIL')][string]$Result,
        [Parameter(Mandatory = $true)][bool]$Full,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Device,
        [AllowEmptyString()][string]$Facebook,
        [AllowEmptyString()][string]$PatchBuild,
        [AllowEmptyString()][string]$InstalledApkSha256,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Started,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Finished,
        [int]$FamiliesRan,
        [int]$Families,
        [int]$Crashes,
        [string[]]$Problems = @()
    )
    return [ordered]@{
        schema = 1; result = $Result; full = $Full
        serial = $Serial; device = $Device; emulator = $Serial.StartsWith('emulator-', [StringComparison]::Ordinal)
        facebook = $Facebook; patchBuild = $PatchBuild; installedApkSha256 = $InstalledApkSha256
        started = $Started.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ'); finished = $Finished.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
        familiesRan = $FamiliesRan; families = $Families; crashes = $Crashes; problems = @($Problems)
    }
}

function Get-BundlePatchBuild {
    <#
    .SYNOPSIS
        The patch build identity a patched app reports for this bundle, or $null when the bundle
        carries no identity that checks out against its own contents.
    .DESCRIPTION
        The same string BundleIdentity.fromJar builds, out of Get-BundleIdentityFacts
        (release-receipt.ps1), which recomputes the payload hash rather than reading it.
    #>
    param([Parameter(Mandatory = $true)][string]$BundlePath)
    $facts = Get-BundleIdentityFacts -BundlePath $BundlePath
    if (-not $facts.Valid) { return $null }
    $source = if ($facts.SourceState -ceq 'unknown') { 'unknown' } else { "$($facts.SourceState):$($facts.SourceCommit); tree=$($facts.SourceTree)" }
    return "sha256=$($facts.PayloadSha256); source=$source; inputs=$($facts.InputSha256)"
}

function Test-WalkReceipt {
    <#
    .SYNOPSIS
        Whether the receipt at -Path is a full, passing walk of the build -PatchBuild names, on a
        release phone or an emulator. Answers @{ Valid; Reason; Receipt }.
    #>
    param([Parameter(Mandatory = $true)][string]$Path, [AllowEmptyString()][string]$PatchBuild)
    function Fail([string]$Reason) { return [pscustomobject]@{ Valid = $false; Reason = $Reason; Receipt = $null } }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return Fail "there's no walk receipt at $Path" }
    try { $receipt = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
    catch { return Fail "the walk receipt doesn't read as JSON: $($_.Exception.Message)" }
    if ($null -eq $receipt -or ($receipt.schema -isnot [int] -and $receipt.schema -isnot [long]) -or $receipt.schema -ne 1) {
        return Fail "the walk receipt isn't schema 1"
    }
    if ($receipt.result -isnot [string] -or $receipt.result -cne 'PASS') {
        return Fail "the walk ended $(if ($receipt.result -is [string] -and $receipt.result) { $receipt.result } else { 'with no result' }), not PASS"
    }
    if ($receipt.full -isnot [bool] -or -not $receipt.full) {
        return Fail 'the walk skipped part of the audit (-SkipWalk, -SkipPages or -KeepSettings), so it was not the whole app'
    }
    if (($receipt.crashes -isnot [int] -and $receipt.crashes -isnot [long]) -or $receipt.crashes -ne 0) {
        return Fail 'the walk recorded a Facebook crash'
    }
    if ($receipt.device -isnot [string] -or $receipt.serial -isnot [string] -or $receipt.emulator -isnot [bool]) {
        return Fail "the walk receipt doesn't say which device walked it"
    }
    $isEmulator = $receipt.serial.StartsWith('emulator-', [StringComparison]::Ordinal)
    if ($receipt.emulator -ne $isEmulator -or (-not $isEmulator -and $WalkReceiptPhones -cnotcontains $receipt.device)) {
        return Fail "$($receipt.device) isn't one of the release walk phones ($($WalkReceiptPhones -join ', ')) or an emulator"
    }
    if ([string]::IsNullOrEmpty($PatchBuild)) { return Fail 'the bundle carries no patch build identity to hold the walk to' }
    if ($receipt.patchBuild -isnot [string] -or $receipt.patchBuild -cne $PatchBuild) {
        return Fail "the walked app reported patch build '$($receipt.patchBuild)', and this bundle is '$PatchBuild'"
    }
    return [pscustomobject]@{ Valid = $true; Reason = $null; Receipt = $receipt }
}
