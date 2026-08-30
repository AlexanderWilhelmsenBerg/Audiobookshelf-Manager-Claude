# Creates or adopts BookWave's local upload/release signing key and configures Gradle to use it.
# The key and passwords stay outside the repository. Run this once before 06-release-apk.ps1.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')

function Read-DefaultValue {
    param(
        [Parameter(Mandatory)][string]$Prompt,
        [Parameter(Mandatory)][string]$Default
    )

    $answer = Read-Host "$Prompt [$Default]"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $Default }
    return $answer.Trim()
}

function Read-ConfirmedPassword {
    param([Parameter(Mandatory)][string]$Label)

    while ($true) {
        $firstSecure = Read-Host "$Label (12 or more characters)" -AsSecureString
        $secondSecure = Read-Host "Repeat $Label" -AsSecureString
        $first = [System.Net.NetworkCredential]::new('', $firstSecure).Password
        $second = [System.Net.NetworkCredential]::new('', $secondSecure).Password

        if ($first.Length -lt 12) {
            Write-Warn 'Use at least 12 characters.'
            continue
        }
        if ($first -cne $second) {
            Write-Warn 'The passwords did not match. Try again.'
            continue
        }
        return $first
    }
}

function Resolve-UserPath {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$UserDirectory
    )

    $expanded = [Environment]::ExpandEnvironmentVariables($Path)
    if ($expanded -eq '~') { $expanded = $UserDirectory }
    if ($expanded.StartsWith('~\') -or $expanded.StartsWith('~/')) {
        $expanded = Join-Path $UserDirectory $expanded.Substring(2)
    }
    if (-not [System.IO.Path]::IsPathRooted($expanded)) {
        $expanded = Join-Path $UserDirectory $expanded
    }
    return [System.IO.Path]::GetFullPath($expanded)
}

function Test-PathInsideRepository {
    param(
        [Parameter(Mandatory)][string]$Candidate,
        [Parameter(Mandatory)][string]$Repository
    )

    $separator = [System.IO.Path]::DirectorySeparatorChar
    $repositoryPrefix = [System.IO.Path]::GetFullPath($Repository).TrimEnd('\', '/') + $separator
    $candidatePath = [System.IO.Path]::GetFullPath($Candidate)
    return $candidatePath.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)
}

function ConvertTo-PropertiesValue {
    param([AllowEmptyString()][string]$Value)

    $result = [System.Text.StringBuilder]::new()
    for ($index = 0; $index -lt $Value.Length; $index++) {
        $character = $Value[$index]
        $code = [int]$character
        switch ($character) {
            '\' { [void]$result.Append('\\') }
            "`t" { [void]$result.Append('\t') }
            "`r" { [void]$result.Append('\r') }
            "`n" { [void]$result.Append('\n') }
            "`f" { [void]$result.Append('\f') }
            '=' { [void]$result.Append('\=') }
            ':' { [void]$result.Append('\:') }
            '#' { [void]$result.Append('\#') }
            '!' { [void]$result.Append('\!') }
            ' ' {
                if ($index -eq 0) { [void]$result.Append('\ ') } else { [void]$result.Append(' ') }
            }
            default {
                if ($code -lt 0x20 -or $code -gt 0x7e) {
                    [void]$result.Append(('\u{0:x4}' -f $code))
                } else {
                    [void]$result.Append($character)
                }
            }
        }
    }
    return $result.ToString()
}

function Resolve-Keytool {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\keytool.exe')) }

    $localJdkRoot = Join-Path $RepoRoot '.gradle\local-toolchain\jdk'
    if (Test-Path -LiteralPath $localJdkRoot) {
        Get-ChildItem -LiteralPath $localJdkRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin\keytool.exe')) }
    }

    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) { $candidates.Add($command.Source) }
    return $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}

Write-Step 'BookWave release signing - what this helper creates'
Write-Note 'This creates an upload key for local release APKs and future Google Play uploads.'
Write-Note 'Google Play App Signing will hold the end-user signing key; this is the replaceable upload key.'
Write-Warn 'Keep an encrypted backup of the key and passwords somewhere a lost computer does not take with it.'
Write-Warn 'The passwords must be stored in your user Gradle properties so Gradle can sign non-interactively.'

$userDirectory = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
if ([string]::IsNullOrWhiteSpace($userDirectory)) { throw 'The Windows user directory could not be resolved.' }

$gradleHome = if ($env:GRADLE_USER_HOME) {
    Resolve-UserPath -Path $env:GRADLE_USER_HOME -UserDirectory $userDirectory
} else {
    Join-Path $userDirectory '.gradle'
}
$gradleProperties = Join-Path $gradleHome 'gradle.properties'
$defaultKeystore = Join-Path $userDirectory '.bookwave\upload.jks'

Write-Step 'Step 1 of 6 - Choose where the key lives'
$enteredPath = Read-DefaultValue -Prompt 'Keystore path (must stay outside the repository)' -Default $defaultKeystore
$keystorePath = Resolve-UserPath -Path $enteredPath -UserDirectory $userDirectory
if (Test-PathInsideRepository -Candidate $keystorePath -Repository $RepoRoot) {
    throw "The key cannot be stored inside the repository: $keystorePath"
}
$useExistingKey = Test-Path -LiteralPath $keystorePath
if ($useExistingKey) {
    Write-Warn "A file already exists at $keystorePath."
    $existingAnswer = Read-Host 'Use and verify this existing keystore without modifying it? [y/N]'
    if ($existingAnswer -notmatch '^y(?:es)?$') {
        throw 'Cancelled rather than overwriting an existing signing key. Choose a new path to create another key.'
    }
}

Write-Step 'Step 2 of 6 - Choose the key identity'
$alias = Read-DefaultValue -Prompt 'Key alias' -Default 'upload'
if ($alias -notmatch '^[A-Za-z0-9._-]+$') {
    throw 'The alias may contain only letters, numbers, dot, underscore, and hyphen.'
}
$distinguishedName = Read-DefaultValue `
    -Prompt 'Certificate identity' `
    -Default 'CN=BookWave Upload, OU=Android, O=BookWave, C=NO'

Write-Step 'Step 3 of 6 - Choose passwords'
$storePassword = Read-ConfirmedPassword -Label 'Keystore password'
$separateAnswer = Read-Host 'Use a different password for the key itself? [y/N]'
$keyPassword = if ($separateAnswer -match '^y(?:es)?$') {
    Read-ConfirmedPassword -Label 'Key password'
} else {
    $storePassword
}

Write-Step 'Step 4 of 6 - The debug build is not affected by this key'
Write-Note 'This key signs release builds only. The debug build has its own stable key at'
Write-Note '~/.bookwave/debug.keystore, created by the build and adopted from ~/.android/debug.keystore when'
Write-Note 'one exists - so "adb install -r" stays an upgrade whether or not you finish this script.'
Write-Note 'This prompt used to ask whether to sign debug with the upload key. It no longer exists: making the'
Write-Note 'debug signature depend on whether these values were set is what caused the uninstall it warned about.'

$keytool = Resolve-Keytool
if (-not $keytool) {
    throw 'keytool.exe was not found. Run . .\scripts\Set-BookWavePath.ps1 and retry.'
}

Write-Step 'Step 5 of 6 - Review before creating anything'
Write-Host "  Keystore:          $keystorePath"
Write-Host "  Alias:             $alias"
Write-Host "  Certificate:       $distinguishedName"
Write-Host "  Gradle properties: $gradleProperties"
Write-Host "  Debug builds:      unaffected - they have their own stable key"
Write-Host "  Operation:         $(if ($useExistingKey) { 'Verify and configure existing key' } else { 'Create and configure new key' })"
Write-Note 'Passwords are hidden and will not be printed.'
$confirmationWord = if ($useExistingKey) { 'CONFIGURE' } else { 'CREATE' }
$confirmation = Read-Host "Type $confirmationWord to continue"
if ($confirmation -cne $confirmationWord) {
    Write-Warn 'Cancelled. Nothing was created or changed.'
    return
}

$keystoreDirectory = Split-Path -Parent $keystorePath
New-Item -ItemType Directory -Path $keystoreDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $gradleHome -Force | Out-Null

$storeVariable = 'BOOKWAVE_SETUP_STORE_PASSWORD'
$keyVariable = 'BOOKWAVE_SETUP_KEY_PASSWORD'
[Environment]::SetEnvironmentVariable($storeVariable, $storePassword, 'Process')
[Environment]::SetEnvironmentVariable($keyVariable, $keyPassword, 'Process')
try {
    if ($useExistingKey) {
        Write-Step 'Verifying the existing upload key'
        Write-Note 'The existing keystore will be read but not modified.'
    } else {
        Write-Step 'Creating the 4096-bit RSA upload key'
        Show-Command "keytool -genkeypair -keystore '$keystorePath' -alias '$alias' -keyalg RSA -keysize 4096 -validity 10000 ..."
        $keytoolArguments = @(
            '-genkeypair',
            '-v',
            '-keystore', $keystorePath,
            '-alias', $alias,
            '-keyalg', 'RSA',
            '-keysize', '4096',
            '-validity', '10000',
            '-storetype', 'JKS',
            '-dname', $distinguishedName,
            '-storepass:env', $storeVariable,
            '-keypass:env', $keyVariable
        )
        & $keytool @keytoolArguments
        if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE." }
    }

    $keyDetails = @(& $keytool -list -v -keystore $keystorePath -alias $alias '-storepass:env' $storeVariable)
    if ($LASTEXITCODE -ne 0) {
        throw 'The keystore could not be verified. Check its password and alias; no Gradle settings were changed.'
    }
    $fingerprint = $keyDetails | Where-Object { $_ -match '^\s*SHA256:' } | Select-Object -First 1
} finally {
    [Environment]::SetEnvironmentVariable($storeVariable, $null, 'Process')
    [Environment]::SetEnvironmentVariable($keyVariable, $null, 'Process')
}

$existingLines = if (Test-Path -LiteralPath $gradleProperties) {
    @(Get-Content -LiteralPath $gradleProperties)
} else {
    @()
}
# `debug` is still matched so a stale `bookwave.signing.debug=` line written by an earlier version of this
# script is removed rather than left behind meaning nothing.
$managedPattern = '^\s*bookwave\.signing\.(storeFile|storePassword|keyAlias|keyPassword|debug)\s*='
$keptLines = @($existingLines | Where-Object { $_ -notmatch $managedPattern })
$propertyLines = @(
    'bookwave.signing.storeFile=' + (ConvertTo-PropertiesValue ($keystorePath -replace '\\', '/')),
    'bookwave.signing.storePassword=' + (ConvertTo-PropertiesValue $storePassword),
    'bookwave.signing.keyAlias=' + (ConvertTo-PropertiesValue $alias),
    'bookwave.signing.keyPassword=' + (ConvertTo-PropertiesValue $keyPassword)
)
$newContents = [System.Collections.Generic.List[string]]::new()
$keptLines | ForEach-Object { $newContents.Add($_) }
if ($newContents.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($newContents[$newContents.Count - 1])) {
    $newContents.Add('')
}
$newContents.Add('# BookWave signing key (generated by scripts/device-test/06-create-signing-key.ps1)')
$propertyLines | ForEach-Object { $newContents.Add($_) }
$propertiesText = ($newContents.ToArray() -join [Environment]::NewLine) + [Environment]::NewLine
[System.IO.File]::WriteAllText(
    $gradleProperties,
    $propertiesText,
    [System.Text.UTF8Encoding]::new($false)
)

$writtenPropertyNames = @(
    Get-Content -LiteralPath $gradleProperties |
        ForEach-Object {
            if ($_ -match '^\s*bookwave\.signing\.(storeFile|storePassword|keyAlias|keyPassword|debug)\s*=\s*.+$') {
                $Matches[1]
            }
        } |
        Sort-Object -Unique
)
$missingWrittenProperties = @(
    @('storeFile', 'storePassword', 'keyAlias', 'keyPassword', 'debug') |
        Where-Object { $_ -notin $writtenPropertyNames }
)
if ($missingWrittenProperties.Count -gt 0) {
    throw "The Gradle properties file did not preserve one setting per line. Missing: $($missingWrittenProperties -join ', ')."
}

Write-Step 'Refreshing Gradle signing settings'
Write-Note 'A running Gradle daemon can retain an older, partial copy of the user properties.'
try {
    Invoke-Gradle '--stop'
} catch {
    Write-Warn "Gradle daemon shutdown reported: $($_.Exception.Message)"
    Write-Note 'Run .\gradlew.bat --stop manually before building the release.'
}

Write-Step 'Step 6 of 6 - Finished'
if ($useExistingKey) {
    Write-Ok "Verified and configured existing key $keystorePath"
} else {
    Write-Ok "Created $keystorePath"
}
Write-Ok "Configured $gradleProperties"
if ($fingerprint) { Write-Host "  Certificate $($fingerprint.Trim())" }
Write-Ok 'Debug signing is untouched by this key, so this setup does not force a debug-app uninstall.'
Write-Warn 'Back up the keystore and both passwords now. Do not copy them into this repository.'
Write-Host ''
Write-Host 'Next command:' -ForegroundColor Cyan
Write-Host '  & .\scripts\device-test\06-release-apk.ps1'
