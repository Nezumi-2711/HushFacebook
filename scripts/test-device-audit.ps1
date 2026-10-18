<# Tests audit control flow without connecting to a device. #>
$ErrorActionPreference = 'Stop'
$auditPath = Join-Path $PSScriptRoot 'device-audit.ps1'
$parseErrors = $null
$auditAst = [System.Management.Automation.Language.Parser]::ParseFile($auditPath, [ref]$null, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors -join "`n") }
$testFunctions = @('Assert-AuditLease', 'Assert-AuditSteps', 'Assert-AuditForeground', 'Invoke-Adb',
    'Get-AuditLocales', 'Get-AuditNightMode', 'Restore-AuditState', 'Format-Shell',
    'New-AuditBaseline', 'Test-AuditBaseline', 'Publish-AuditBaseline', 'Save-SettingsPages', 'Write-Step', 'Test-AuditDestination', 'Select-AuditTapNode', 'Get-UiNodes', 'Send-Profile', 'Test-AuditLocaleList', 'Get-AuditLauncher', 'Get-Report')
foreach ($functionName in $testFunctions) {
    $definition = $auditAst.Find({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName
    }, $true)
    if (-not $definition) { throw "Missing audit function $functionName" }
    . ([scriptblock]::Create($definition.Extent.Text))
}

. (Join-Path $PSScriptRoot 'device-install.ps1')
$failures = [Collections.Generic.List[string]]::new()
$checks = 0
function Assert-That([bool]$Condition, [string]$Message) {
    $script:checks++
    if (-not $Condition) { $script:failures.Add($Message) }
}
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $threw = $false
    try { & $Action } catch { $threw = $true }
    Assert-That $threw $Message
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('hushfacebook-audit-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$Serial = 'emulator-5554'
$Package = 'com.facebook.katana'
$LeaseDirectory = $testRoot
$LeaseTokenFile = Join-Path $testRoot 'owner.txt'
$leasePath = Join-Path $testRoot "$Serial.json"
$testToken = [Guid]::NewGuid().ToString()
$identityVerified = $true
$adb = 'Invoke-FakeAdb'
$script:adbCalls = [Collections.Generic.List[string]]::new()
$script:foreground = "topResumedActivity=ActivityRecord{1 u0 $Package/.Main t1}"
$script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
$script:uiDump = "<?xml version=`"1.0`"?><hierarchy><node package=`"$Package`" text=`"Comment`" content-desc=`"Comment`" bounds=`"[0,0][20,20]`" /></hierarchy>"
$inputNodeLabel = 'Comment'
function Invoke-FakeAdb {
    $command = $args -join ' '
    $script:adbCalls.Add($command)
    $global:LASTEXITCODE = 0
    if ($script:disconnected) { throw 'Mock disconnect' }
    if ($command -like '* pull */applied.json *') {
        [IO.File]::WriteAllText([string]$args[-1], $(if ($script:wrongSnapshot) { '{"wrong":true}' } else { '{}' }))
    }
    if ($command -like '*cat */page.json*') { return $script:pageAck }
    if ($command -like '*cat */import.txt*') { return 'applied 4' }
    if ($command -like '*dumpsys activity activities') { return $script:foreground }
    if ($command -like '*dumpsys window') {
        if ($script:changeOwnWindowAfterDump -and $null -ne $script:postDumpFocusReads) {
            $script:postDumpFocusReads++
            if ($script:postDumpFocusReads -ge 2) { $script:focusedWindow = "mCurrentFocus=Window{changed u0 $Package/$Package.Main}" }
        }
        return $script:focusedWindow
    }
    if ($command -like '*uiautomator dump*') {
        if ($script:takeFocusOnDump) { $script:focusedWindow = 'mCurrentFocus=Window{2 u0 NotificationShade}' }
        if ($script:changeOwnWindowAfterDump) { $script:postDumpFocusReads = 0 }
        return $script:uiDump
    }
    if ($command -like '*query-activities*') { return $script:launcherQuery }
    if ($command -match "cmd locale set-app-locales .* --locales '([^']*)'$") { $script:actualLocale = $Matches[1] }
    if ($command -like '*cmd locale get-app-locales*') { return "Locales for $Package for user 0 are [$script:actualLocale]" }
    if ($script:refuseLocale -and $command -like '*cmd locale set-app-locales*') { throw 'Mock locale refusal' }
    if ($command -match 'cmd uimode night (yes|no|auto|custom)$') {
        if ($script:refuseNight) { throw 'Mock night-mode refusal' }
        $script:actualNight = $Matches[1]
    } elseif ($command -like '*cmd uimode night') { return "Night mode: $script:actualNight" }
    return 'OK'
}
function Write-TestLease([string]$Owner = $testToken, [double]$Minutes = 25) {
    $body = [ordered]@{ schemaVersion = 1; serial = $Serial; project = 'Hushfacebook'; chatIdentity = 'audit-tests'
        ownershipToken = $Owner; acquiredUtc = [DateTime]::UtcNow.ToString('o')
        expiresUtc = [DateTime]::UtcNow.AddMinutes($Minutes).ToString('o') }
    [IO.File]::WriteAllText($leasePath, ($body | ConvertTo-Json))
}

try {
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'Missing token permitted a wake.'
    [IO.File]::WriteAllText($LeaseTokenFile, $testToken)
    Assert-Throws { & $auditPath -Serial $Serial -ExpectedDevice 'Audit_AVD' -OutDir $testRoot -LeaseDirectory $LeaseDirectory -LeaseTokenFile $LeaseTokenFile } 'An occupied output folder was accepted.'
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'Missing lease permitted a wake.'
    Write-TestLease -Owner 'someone-else'
    Assert-Throws { Invoke-Adb install example.apk } 'Wrong owner permitted an install.'
    Write-TestLease -Minutes -1
    Assert-Throws { Invoke-Adb shell cmd uimode night yes } 'Expired lease permitted a settings change.'
    Write-TestLease
    $badLease = Get-Content -LiteralPath $leasePath -Raw | ConvertFrom-Json
    $badLease.chatIdentity = @('audit-tests')
    [IO.File]::WriteAllText($leasePath, ($badLease | ConvertTo-Json))
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'A nonscalar lease identity permitted input.'
    Write-TestLease
    $badLease = Get-Content -LiteralPath $leasePath -Raw | ConvertFrom-Json
    $badLease.expiresUtc = @('2099-01-01T00:00:00Z')
    [IO.File]::WriteAllText($leasePath, ($badLease | ConvertTo-Json))
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'A nonscalar lease expiry permitted input.'
    Assert-That ($script:adbCalls.Count -eq 0) 'An invalid lease reached ADB.'

    Write-TestLease -Minutes 1
    Assert-AuditLease
    $renewed = Get-Content -Raw $leasePath | ConvertFrom-Json
    Assert-That ([DateTimeOffset]$renewed.expiresUtc -gt [DateTimeOffset]::UtcNow.AddMinutes(20)) 'Lease was not renewed.'
    Assert-That ($renewed.ownershipToken -ceq $testToken) 'Renewal changed the owner token.'
    $held = [IO.File]::Open($leasePath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try { Assert-Throws { Invoke-Adb shell input tap 10 10 } 'A locked lease permitted input.' }
    finally { $held.Dispose() }

    foreach ($unsafe in @('tap:Like', 'tap:Follow', 'tap:Add friend', 'tap:Send', 'tap:Alice', 'fb://friends', 'fb://feed; input tap 1 1')) {
        $Visit = @($unsafe)
        Assert-Throws { Assert-AuditSteps } "Unsafe step accepted: $unsafe"
    }
    $Visit = @('fb://feed', 'tap:Comment', 'back', 'tap:Feeds', 'tap:Settings and privacy', 'longpress')
    Assert-AuditSteps

    $identityVerified = $false
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_WAKEUP } 'Unverified identity permitted input.'
    $identityVerified = $true
    $script:foreground = 'topResumedActivity=ActivityRecord{1 u0 com.android.settings/.Settings t1}'
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'Other foreground package permitted a tap.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'A tap escaped the foreground guard.'
    $script:foreground = "topResumedActivity=ActivityRecord{1 u0 $Package/.Main t1}`nmResumedActivity: ActivityRecord{1 u0 $Package/.Main t1}"
    Invoke-Adb shell input tap 10 10 | Out-Null
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input tap 10 10' }).Count -eq 1) 'Valid foreground input did not run once.'
    $script:foreground += "`nmResumedActivity: ActivityRecord{2 u0 com.android.settings/.Settings t2}"
    Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'Multiple foreground packages permitted input.'
    $script:foreground = "topResumedActivity=ActivityRecord{1 u0 $Package/.Main t1}"
    foreach ($focus in @('mCurrentFocus=Window{2 u0 NotificationShade}',
            'mCurrentFocus=Window{2 u0 com.google.android.permissioncontroller/.PermissionActivity}', 'mCurrentFocus=null')) {
        $script:focusedWindow = $focus
        $script:adbCalls.Clear()
        Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'An overlay permitted input while Facebook remained resumed.'
        Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'Input escaped the window-focus guard.'
    }
    $script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
    $savedDump = $script:uiDump
    $script:uiDump = '<?xml version="1.0"?><hierarchy />'
    Assert-Throws { Invoke-Adb shell input keyevent KEYCODE_BACK } 'An empty current Facebook window permitted Back.'
    $script:uiDump = $savedDump
    Assert-AuditForeground
    $inputWindow = $script:auditWindowIdentity
    Invoke-Adb shell input swipe 10 20 10 5 | Out-Null
    $script:focusedWindow = "mCurrentFocus=Window{changed u0 $Package/$Package.Main}"
    Assert-Throws { Invoke-Adb shell input swipe 10 20 10 5 } 'A different Facebook window permitted a stale route swipe.'
    $inputWindow = $null
    $script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
    Assert-Throws { Invoke-Adb shell input tap 30 30 } 'A moved navigation node permitted a stale-coordinate tap.'
    # The S22's menu row repeats its label on an unclickable child that also holds the tap point.
    $script:uiDump = "<?xml version=`"1.0`"?><hierarchy><node package=`"$Package`" text=`"`" content-desc=`"Settings and privacy`" clickable=`"true`" bounds=`"[0,1966][1080,2091]`"><node package=`"$Package`" text=`"`" content-desc=`"Settings and privacy`" clickable=`"false`" bounds=`"[136,2001][956,2058]`" /></node></hierarchy>"
    $inputNodeLabel = 'Settings and privacy'
    $script:adbCalls.Clear()
    Invoke-Adb shell input tap 540 2028 | Out-Null
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* input tap 540 2028' }).Count -eq 1) 'A row with its label repeated inside it refused the tap.'
    Assert-Throws { Invoke-Adb shell input tap 540 1900 } 'A tap outside the selected row was permitted.'
    $script:uiDump = $savedDump
    $inputNodeLabel = 'Comment'
    $script:takeFocusOnDump = $true
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'An overlay arriving during the dump permitted input.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'Input escaped after focus changed during the dump.'
    $script:takeFocusOnDump = $false
    $script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
    $script:changeOwnWindowAfterDump = $true
    $script:postDumpFocusReads = $null
    $script:adbCalls.Clear()
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'A different Facebook window arriving after node collection permitted a tap.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* input *' }).Count) 'A tap used nodes from the previous Facebook window.'
    $script:changeOwnWindowAfterDump = $false
    $script:postDumpFocusReads = $null
    $script:focusedWindow = "mCurrentFocus=Window{1 u0 $Package/$Package.Main}"
    function New-TestNode([string]$Label, [string]$Class = 'android.view.View', [string]$Owner = $Package) {
        return [pscustomobject]@{ text = $Label; 'content-desc' = $Label; package = $Owner; class = $Class }
    }
    $feedNodes = @((New-TestNode "What's on your mind?"), (New-TestNode 'Go to profile'))
    Assert-That (Test-AuditDestination 'fb://feed' $feedNodes) 'A proved feed was rejected.'
    Assert-That (Test-AuditDestination 'fb://feed' @((New-TestNode 'Facebook logo'))) 'Home with its composer hidden was rejected.'
    Assert-That (Test-AuditDestination 'fb://watch' @((New-TestNode 'Reel details'))) 'The immersive Reels destination was rejected.'
    foreach ($route in @('fb://watch', 'fb://fb_shorts/viewer', 'fb://notifications', 'fb://marketplace', 'fb://profile', 'fb://search', 'fb://bookmarks')) {
        Assert-That (-not (Test-AuditDestination $route $feedNodes)) "Home fallback passed as $route."
        Assert-That (-not (Test-AuditDestination $route @())) "Empty UI passed as $route."
    }
    $destinations = @{
        'fb://watch' = @((New-TestNode 'Reels tab details'))
        'fb://fb_shorts/viewer' = @((New-TestNode 'Reel details'))
        'fb://notifications' = @((New-TestNode 'Notifications'))
        'fb://marketplace' = @((New-TestNode 'Marketplace'), (New-TestNode 'Search Marketplace'))
        'fb://profile' = @((New-TestNode 'Edit profile'), (New-TestNode 'Edit profile picture'))
        'fb://search' = @((New-TestNode 'Search with Meta AI' 'android.widget.EditText'))
        'fb://bookmarks' = @((New-TestNode 'Menu'), (New-TestNode 'Settings and privacy'))
    }
    foreach ($route in $destinations.Keys) {
        Assert-That (Test-AuditDestination $route $destinations[$route]) "Proved destination rejected: $route."
        $foreign = @($destinations[$route] | ForEach-Object { New-TestNode $_.text $_.class 'com.other.app' })
        Assert-That (-not (Test-AuditDestination $route $foreign)) "Foreign package passed as $route."
    }
    # The pages the links open on an account with no profile picture and a warm app.
    $linkedPages = @{
        'fb://notifications' = @((New-TestNode "Manage the notification$([char]0x2019)s settings" 'android.widget.Button'))
        'fb://profile' = @((New-TestNode 'Edit profile'), (New-TestNode 'Add profile picture'))
        'fb://bookmarks' = @((New-TestNode 'Settings'), (New-TestNode 'Settings and privacy'))
    }
    foreach ($route in $linkedPages.Keys) {
        Assert-That (Test-AuditDestination $route $linkedPages[$route]) "Proved linked page rejected: $route."
        if ($linkedPages[$route].Count -gt 1) {
            Assert-That (-not (Test-AuditDestination $route @($linkedPages[$route][0]))) "Half a linked page passed as $route."
        }
        $foreign = @($linkedPages[$route] | ForEach-Object { New-TestNode $_.text $_.class 'com.other.app' })
        Assert-That (-not (Test-AuditDestination $route $foreign)) "Foreign package passed as linked $route."
    }
    # The S22's menu row repeats its label on a child that isn't clickable.
    function New-TapNode([string]$Label, [string]$Bounds, [string]$Clickable, [string]$Owner = $Package) {
        return [pscustomobject]@{ text = ''; 'content-desc' = $Label; package = $Owner; bounds = $Bounds; clickable = $Clickable }
    }
    $row = New-TapNode 'Settings and privacy' '[0,1966][1080,2091]' 'true'
    $rowText = New-TapNode 'Settings and privacy' '[136,2001][956,2058]' 'false'
    $gear = New-TapNode 'Settings' '[962,87][1063,188]' 'true'
    Assert-That ([object]::ReferenceEquals((Select-AuditTapNode 'Settings and privacy' @($gear, $rowText, $row)), $row)) 'A row with its label repeated inside it was not tapped.'
    Assert-That ([object]::ReferenceEquals((Select-AuditTapNode 'Settings' @($gear, $row)), $gear)) 'A lone matching node was not tapped.'
    Assert-That ($null -eq (Select-AuditTapNode 'Settings and privacy' @($row, (New-TapNode 'Settings and privacy' '[0,100][1080,200]' 'true')))) 'Two clickable matches picked one.'
    Assert-That ($null -eq (Select-AuditTapNode 'Settings and privacy' @($row, (New-TapNode 'Settings and privacy' '[0,1900][1080,2058]' 'false')))) 'A match outside the clickable row was ignored.'
    Assert-That ($null -eq (Select-AuditTapNode 'Settings and privacy' @($rowText, (New-TapNode 'Settings and privacy' '[136,1001][956,1058]' 'false')))) 'Two unclickable matches picked one.'
    Assert-That ($null -eq (Select-AuditTapNode 'Settings and privacy' @((New-TapNode 'Settings and privacy' '[0,1966][1080,2091]' 'true' 'com.other.app')))) 'A foreign node was tapped.'
    Write-TestLease -Owner 'replacement'
    Assert-Throws { Invoke-Adb shell input tap 10 10 } 'Ownership loss during the walk permitted input.'

    Write-TestLease
    $OutDir = $testRoot
    $AuditDir = "/sdcard/Android/data/$Package/files/hushfacebook-audit"
    $stateBefore = @{ night = 'auto'; locales = 'de-DE,en-US' }
    $KeepSettings = $false
    $problems = [Collections.Generic.List[string]]::new()
    [IO.File]::WriteAllText((Join-Path $OutDir 'before.json'), '{}')
    function Start-Facebook { $script:restarts++ }
    $script:adbCalls.Clear()
    Assert-That ((Send-Profile '{}') -ceq 'applied 4') 'The import acknowledgement was not read.'
    $commands = @($script:adbCalls)
    $stopAt = [Array]::FindIndex($commands, [Predicate[string]]{ param($line) $line -like '*am force-stop*' })
    $clearAt = [Array]::FindIndex($commands, [Predicate[string]]{ param($line) $line -like '*rm -f */import.txt' })
    $pushAt = [Array]::FindIndex($commands, [Predicate[string]]{ param($line) $line -like '*push */settings.json' })
    Assert-That ($stopAt -ge 0 -and $clearAt -gt $stopAt -and $pushAt -gt $clearAt) 'A profile was exposed before stopping Facebook and clearing old acknowledgements.'
    function Send-Profile([string]$Json) {
        if ($script:refuseSettings) { return 'refused: fixture failure' }
        return 'applied 4'
    }
    $script:actualLocale = 'vi-VN'
    $script:actualNight = 'yes'
    $script:restarts = 0
    $script:adbCalls.Clear()
    Assert-That (Restore-AuditState) 'A successful restore failed.'
    Assert-That ($script:actualLocale -ceq 'de-DE,en-US' -and $script:actualNight -eq 'auto') 'Original locale/theme did not round-trip.'
    Assert-That (@($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count -eq 1) 'Successful restore did not clean up once.'

    $script:refuseSettings = $true
    $script:actualLocale = 'vi-VN'
    $script:actualNight = 'yes'
    $script:adbCalls.Clear()
    Assert-That (-not (Restore-AuditState)) 'A refused settings restore passed.'
    Assert-That ($script:actualLocale -ceq 'de-DE,en-US' -and $script:actualNight -eq 'auto') 'Settings refusal prevented independent locale/theme restore.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count) 'Refused restore deleted device recovery data.'
    Assert-That (Test-Path (Join-Path $OutDir 'before.json')) 'Refused restore lost its local backup.'
    $script:refuseSettings = $false
    $script:wrongSnapshot = $true
    Assert-That (-not (Restore-AuditState)) 'An applied but different settings snapshot passed.'
    $script:wrongSnapshot = $false
    $script:refuseNight = $true
    Assert-That (-not (Restore-AuditState)) 'A failed night restore passed.'
    $script:refuseNight = $false
    $script:disconnected = $true
    Assert-That (-not (Restore-AuditState)) 'A disconnected restore passed.'
    $script:disconnected = $false
    $script:refuseLocale = $true
    $script:actualLocale = 'vi-VN'
    $script:actualNight = 'yes'
    $script:adbCalls.Clear()
    $problems.Clear()
    Assert-That (-not (Restore-AuditState)) 'A failed locale restore passed.'
    Assert-That ($script:actualNight -eq 'auto') 'A locale restore failure prevented the theme restore.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count) 'A failed locale restore deleted device recovery data.'
    Assert-That (Test-Path (Join-Path $OutDir 'before.json')) 'A failed locale restore lost its local backup.'
    Assert-That (@($problems | Where-Object { $_ -like 'Could not restore locale*' }).Count -ge 1) 'A failed locale restore was not reported.'
    $script:refuseLocale = $false
    $stateBefore.locales = ''
    $script:actualLocale = 'vi-VN'
    $KeepSettings = $true
    $script:refuseSettings = $true
    $script:adbCalls.Clear()
    Assert-That (Restore-AuditState) 'KeepSettings unexpectedly tried importing settings.'
    Assert-That ($script:actualLocale -ceq '') 'KeepSettings failed to restore an originally empty locale.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '* rm -rf *' }).Count) 'KeepSettings deleted retained recovery data.'

    $version = '583.0.0.39.55'
    $installedHash = 'a' * 64
    $installedAbi = 'arm64-v8a'
    $sdk = 36
    $deviceIdentity = 'Audit_AVD'
    $recoveryGuard = $auditAst.Find({ param($node)
        $node -is [System.Management.Automation.Language.IfStatementAst] -and
            $node.Extent.Text.StartsWith('if (($savedState.schema')
    }, $true)
    Assert-That ($null -ne $recoveryGuard) 'Recovery snapshot validation is missing.'
    $validateRecovery = [scriptblock]::Create($recoveryGuard.Extent.Text)
    foreach ($brokenLocale in @($null, 1, @('de'), 'de;input tap 1 1', '---', 'de,,en', '123', 'en-a', 'en-a-bbb-a-ccc', 'de-1901-1901')) {
        $savedState = [pscustomobject]@{ schema = 1; serial = $Serial; device = $deviceIdentity
            package = $Package; night = 'auto'; locales = $brokenLocale }
        Assert-Throws { & $validateRecovery } 'An invalid original locale passed the recovery snapshot gate.'
    }
    $savedState.locales = ''
    & $validateRecovery
    foreach ($field in @('schema', 'serial', 'device', 'package', 'night')) {
        $malformedState = $savedState | ConvertTo-Json | ConvertFrom-Json
        $malformedState.$field = @($malformedState.$field, $malformedState.$field)
        $savedState = $malformedState
        Assert-Throws { & $validateRecovery } "A nonscalar recovery $field passed validation."
        $savedState = [pscustomobject]@{ schema = 1; serial = $Serial; device = $deviceIdentity
            package = $Package; night = 'auto'; locales = '' }
    }
    foreach ($validLocale in @('', 'de-DE,en-US', 'en-US-u-ca-gregory', 'en-x-a', 'x-private', 'zh-Hant-TW', 'en-GB-oed', 'i-klingon', 'de-CH-1901')) {
        Assert-That (Test-AuditLocaleList $validLocale) "A well-formed locale list was rejected: $validLocale"
    }
    $On = @('one')
    $Off = @('two')
    $OptionalVisit = @('tap:Comment')
    $Locales = @('de')
    $Swipes = 4
    $MaxScrolls = 0
    [IO.File]::WriteAllText((Join-Path $OutDir 'applied.json'), '{}')
    $report = "app: $Package $version (2147483647)`npatch_build: sha256=$('b' * 64); source=clean:$('c' * 40); tree=$('d' * 40); inputs=$('e' * 64)`n"
    $candidate = New-AuditBaseline $report @('family') @('other')
    $expected = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    Assert-That (Test-AuditBaseline $expected $candidate) 'Matching baseline was rejected.'
    foreach ($field in @('schema', 'facebook', 'patchBuild', 'installedApkSha256', 'abi', 'sdk', 'device', 'settingsSha256')) {
        $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        $different.$field = $(if ($field -in @('sdk','schema')) { 999 } else { 'different' })
        Assert-That (-not (Test-AuditBaseline $different $candidate)) "Different baseline $field passed."
        $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        $different.$field = @($different.$field, $different.$field)
        Assert-That (-not (Test-AuditBaseline $different $candidate)) "Nonscalar baseline $field passed."
    }
    $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    $different.profile.on = @('other')
    Assert-That (-not (Test-AuditBaseline $different $candidate)) 'Different profile switches passed.'
    $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
    $different.profile = @($different.profile)
    Assert-That (-not (Test-AuditBaseline $different $candidate)) 'A wrapped profile object passed.'
    foreach ($badFamilies in @($null, 'family', @(), @(1))) {
        $different = $candidate | ConvertTo-Json -Depth 6 | ConvertFrom-Json
        $different.ranOnWalk = $badFamilies
        Assert-That (-not (Test-AuditBaseline $different $candidate)) 'An absent or malformed baseline family list passed.'
    }
    Assert-Throws { New-AuditBaseline ($report -replace 'sha256=', 'unknown=') @() @() } 'Unknown patch build was accepted.'

    $Expectations = Join-Path $testRoot 'baseline.json'
    $original = '{"original":true}'
    [IO.File]::WriteAllText($Expectations, $original)
    $WriteBaseline = $true
    $SkipWalk = $false
    $SkipPages = $false
    $KeepSettings = $false
    $recovered = $true
    foreach ($cause in @('crash', 'wrong page', 'import refusal', 'restore refusal')) {
        $problems.Clear()
        $problems.Add($cause)
        Assert-That (-not (Publish-AuditBaseline $candidate)) "Baseline promoted after $cause."
        Assert-That ([IO.File]::ReadAllText($Expectations) -ceq $original) "Trusted baseline changed after $cause."
    }
    $problems.Clear()
    $recovered = $false
    Assert-That (-not (Publish-AuditBaseline $candidate)) 'Baseline promoted without recovery.'
    $recovered = $true
    $SkipPages = $true
    Assert-That (-not (Publish-AuditBaseline $candidate)) 'Baseline promoted after skipping pages.'
    $SkipPages = $false
    Assert-That (Publish-AuditBaseline $candidate) 'Successful full audit did not promote its baseline.'
    Assert-That ((Get-Content -Raw $Expectations | ConvertFrom-Json).schema -eq 2) 'Promoted baseline was not schema 2.'

    function Start-Sleep { param([int]$Seconds, [int]$Milliseconds) }
    function Get-SettingsPages { return 'News feed' }
    function Close-Settings { }
    function Get-ScreenText { return $script:screenText }
    function Save-Screenshot([string]$Path) { }
    $SettleSeconds = 0
    $translationTables = Join-Path $PSScriptRoot '..\extensions\shared\library\src\main\l10n'
    $Entry = "$Package/.LoginActivity"
    $Extra = 'app.morphe.extension.facebook'
    $swipeX = 10; $swipeFrom = 20; $swipeTo = 5
    # The launcher entry: default, alternate icon, none and several.
    $realStart = ($auditAst.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Start-Facebook' }, $true)).Extent.Text.Replace('function Start-Facebook', 'function Start-FacebookReal')
    . ([scriptblock]::Create($realStart))
    $LauncherClass = 'com.facebook.katana.LoginActivity'
    $alias = 'com.facebook.katana.LoginActivity.fab_ic'
    $script:launcherQuery = @('1 activities found:', "  $Package/.LoginActivity")
    Assert-That ((Get-AuditLauncher) -ceq $LauncherClass) 'The default launcher was not resolved.'
    $script:launcherQuery = @('1 activities found:', "  $Package/$alias")
    Assert-That ((Get-AuditLauncher) -ceq $alias) 'The enabled alternate launcher alias was not resolved.'
    $script:launcherQuery = @('1 activities found:', "  $Package/.LoginActivity.fab_ic")
    Assert-That ((Get-AuditLauncher) -ceq $alias) 'A short alias name was not expanded.'
    $script:launcherQuery = @('1 activities found:', "  $Package/com.facebook.katana.OtherActivity", "  com.other.app/.Main")
    Assert-Throws { Get-AuditLauncher } 'Only unrelated activities passed as a launcher.'
    $script:launcherQuery = @('No activities found')
    Assert-Throws { Get-AuditLauncher } 'A missing launcher passed.'
    $script:launcherQuery = @('0 activities found:', '')
    Assert-Throws { Get-AuditLauncher } 'An empty launcher query passed.'
    $script:launcherQuery = @('2 activities found:', "  $Package/.LoginActivity", "  $Package/$alias")
    Assert-Throws { Get-AuditLauncher } 'Two enabled launchers passed as one.'
    $script:launcherQuery = @("  $Package/.LoginActivity", "  $Package/.LoginActivity")
    Assert-That ((Get-AuditLauncher) -ceq $LauncherClass) 'One launcher listed twice was treated as ambiguous.'
    foreach ($launcher in @($LauncherClass, $alias)) {
        $Entry = "$Package/$launcher"
        $script:adbCalls.Clear()
        Start-FacebookReal
        try { Get-Report (Join-Path $OutDir 'report-launcher.txt') | Out-Null } catch { }
        $script:pageAck = '{"schema":2,"route":"News feed","title":"Newsfeed","locale":"de-DE"}'
        $script:screenText = @('Newsfeed')
        Save-SettingsPages 'dark' | Out-Null
        $starts = @($script:adbCalls | Where-Object { $_ -like '*am start*' -and $_ -notlike '*android.intent.action.VIEW*' })
        Assert-That ($starts.Count -ge 3) "Launch commands were not issued for $launcher."
        Assert-That (@($starts | Where-Object { $_ -notlike "* -n $Package/$launcher*" }).Count -eq 0) "A launch or extra missed the active launcher $launcher."
        Assert-That (@($starts | Where-Object { $_ -like '*OPEN_SETTINGS true*' -and $_ -like "* -n $Package/$launcher *" }).Count -ge 1) "OPEN_SETTINGS did not go to $launcher."
        Assert-That (@($starts | Where-Object { $_ -like '*AUDIT_REPORT true*' -and $_ -like "* -n $Package/$launcher *" }).Count -ge 1) "AUDIT_REPORT did not go to $launcher."
        Assert-That (-not @($script:adbCalls | Where-Object { $_ -match 'pm (enable|disable)|setEnabledSetting|cmd package (enable|disable)' }).Count) 'The audit changed a component state.'
    }
    $Entry = $null
    $script:adbCalls.Clear()
    Assert-Throws { Start-FacebookReal } 'An unresolved launcher still started Facebook.'
    Assert-Throws { Get-Report (Join-Path $OutDir 'report-none.txt') } 'An unresolved launcher still requested a report.'
    Assert-Throws { Save-SettingsPages 'dark' } 'An unresolved launcher still opened settings.'
    Assert-That (-not @($script:adbCalls | Where-Object { $_ -like '*am *' }).Count) 'An unresolved launcher reached ADB.'
    $Entry = "$Package/.LoginActivity"
    $script:foreground = "topResumedActivity=ActivityRecord{1 u0 $Package/.Main t1}"
    $script:screenText = @('Newsfeed')
    $script:pageAck = '{"schema":2,"route":"News feed","title":"Newsfeed","locale":"de-DE"}'
    Assert-That (@(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'Proved translated page was rejected.'
    $script:pageAck = '{"schema":2,"route":"Playback","title":"Newsfeed","locale":"de-DE"}'
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'Wrong localized page was accepted.'
    $script:screenText = @('News feed')
    $script:pageAck = '{"schema":2,"route":"News feed","title":"News feed","locale":"en-US"}'
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'An English page passed a requested German pass.'
    $script:pageAck = '{"schema":2,"route":"News feed","title":"News feed","locale":"de-DE"}'
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'An English title under German configuration passed.'
    $script:pageAck = '{"schema":2,"route":"News feed","title":"News feed"}'
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'A missing effective locale passed a localized pass.'
    $script:pageAck = ''
    Assert-That (-not @(Save-SettingsPages 'dark-de' 'de')[0].Opened) 'Missing page acknowledgement passed.'
    $script:screenText = @()
    Assert-Throws { Save-SettingsPages 'dark-de' } 'Empty UI nodes passed.'
} finally {
    # Only the exact uniquely-created test directory is removed.
    if ([IO.Path]::GetDirectoryName($testRoot.TrimEnd('\')) -ne [IO.Path]::GetTempPath().TrimEnd('\')) {
        throw 'Refusing cleanup outside the test temp directory.'
    }
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
if ($failures.Count) { throw ($failures -join "`n") }
Write-Host "[device-audit] $checks checks passed"
