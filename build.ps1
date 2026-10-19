<#
.SYNOPSIS
  婵炴垶鎸撮崑鎾绘⒑濞嗘儳鏋ょ紒槌栦簽閹风娀骞嬪┑濠傜彲闁诲海鎳撻ˇ鎶剿?idea-hide-trial 闂佸湱绮敮濠傗枎閵忋倕绀嗛柣妤€鐗婇幏閬嶆煕?IntelliJ IDEA闂?
.DESCRIPTION
  闂佺儵鏅涢悺銊ф暜鐎涙顩烽柕澹嫮鐛ラ梺?IDE 闁诲海鎳撻ˇ鎶剿夋繝鍥ㄥ剮妞ゆ棁鍋愮粔鍨槈閹炬剚鍎戞繛?lib/*.jar 婵炶揪绲剧划鍫㈡嫻閻旇櫣纾介柡宥庡墰濡?classpath闂佹寧绋戦張顒€螞閵堝顥嗛柍?Gradle/Maven 婵炴垶鎸稿ù椋庣礊婢跺瞼纾兼繝褍绨遍崑?
.PARAMETER NoInstall
  闂佸憡鐟禍婵嬫倵椤栨凹鍤?jar闂佹寧绋戞總鏃傜箔婢跺绶炵€广儱鎳庨悡鎴︽煕?IDE 闂佸湱绮敮濠傗枎閵忋倖鍎庢い鏃囧亹缁夊潡鏌?
.EXAMPLE
  .\build.ps1
  .\build.ps1 -NoInstall
#>
[CmdletBinding()]
param(
    [string[]]$IdeaHomes = @(
        "D:\Program Files\JetBrains\IntelliJ IDEA 2026.1"
    ),
    [switch]$NoInstall
)

$ErrorActionPreference = "Stop"

$root     = $PSScriptRoot
$srcDir   = Join-Path $root "src\main\java"
$resDir   = Join-Path $root "src\main\resources"
$buildDir = Join-Path $root "build"
$classes  = Join-Path $buildDir "classes"
$jarName  = "idea-ext-hx-1.1.0.jar"

# ---------- 1. 濠电偞鎸搁幊鎰板箖?----------
if (Test-Path -LiteralPath $buildDir) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}
New-Item -ItemType Directory -Path $classes -Force | Out-Null

# ---------- 2. 闂備緡鍋勯ˇ鎵偓?IDE 婵炶揪绲剧划鍫㈡嫻閻旇櫣纾介柡宥庡墰濡差垰銆掑顓犵畾缂佸倸妫楅埞?----------
$compileHome = $IdeaHomes | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $compileHome) {
    Write-Host "No IntelliJ IDEA installation found; please edit `$IdeaHomes at the top of this script."
}
Write-Host "Using compile classpath from: $compileHome"

$classPath = Join-Path $compileHome "lib\*"

# ---------- 3. 缂傚倸鍊归悧鐐烘儊?----------
$sources = Get-ChildItem -LiteralPath $srcDir -Recurse -Filter "*.java" |
           ForEach-Object { $_.FullName }
if (-not $sources) { throw "No Java sources found: $srcDir" }

Write-Host "缂傚倸鍊归悧鐐烘儊?$($sources.Count) 婵炴垶鎼╂禍婵堣姳椤曗偓瀵剟宕堕敂绛嬪仺 (target 17)..."
& javac.exe -encoding UTF-8 --release 17 -nowarn -cp $classPath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac 缂傚倸鍊归悧鐐烘儊瑜庡鍕綇椤愩儛?(exit=$LASTEXITCODE)" }

# ---------- 4. 闂佺懓鐏氶幐鍝モ偓?----------
Copy-Item -Path (Join-Path $resDir "*") -Destination $classes -Recurse -Force
$jarPath = Join-Path $buildDir $jarName
& jar.exe --create --file $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar packaging failed (exit=$LASTEXITCODE)" }
Write-Host "Build OK: $jarPath"
& jar.exe --list --file $jarPath

# ---------- 5. 闁诲海鎳撻ˇ鎶剿夋繝鍥х?IDE 闂佸湱绮敮濠傗枎閵忋倖鍎庢い鏃囧亹缁?----------
if ($NoInstall) {
    Write-Host "-NoInstall specified, skipping install."
exit 0
}

$installed = @()
foreach ($ideaHome in $IdeaHomes) {
    if (-not (Test-Path -LiteralPath $ideaHome)) { continue }

    $vendor = if ($ideaHome -like "*Android Studio*") { "Google" } else { "JetBrains" }
    $product = if ($ideaHome -like "*Android Studio*") { "AndroidStudio" } else { "IntelliJIdea" }
    if ($ideaHome -match "(\d{4}\.\d)") { $versionTag = $Matches[1] } else { continue }

    $pluginsDir = Join-Path $env:APPDATA "$vendor\$product$versionTag\plugins"
    if (-not (Test-Path -LiteralPath $pluginsDir)) {
        Write-Host "Skipped (no plugins dir): $pluginsDir"
        continue
    }

    $dest = Join-Path $pluginsDir "idea-ext-hx\lib"
    if (Test-Path -LiteralPath (Split-Path $dest) ) { Remove-Item -LiteralPath (Split-Path $dest) -Recurse -Force }
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    Copy-Item -LiteralPath $jarPath -Destination $dest -Force

    $installed += "$product$versionTag -> $dest"
}

if ($installed.Count -eq 0) {
    Write-Host "Not installed to any IDE. You can copy $jarPath to the IDE plugins dir manually."
} else {
    Write-Host ""
    Write-Host "Installed. Restart the IDE to take effect:"
    $installed | ForEach-Object { Write-Host "  $_" }
    Write-Host ""
    Write-Host "Uninstall: delete the idea-ext-hx folder above, then restart the IDE."
}
