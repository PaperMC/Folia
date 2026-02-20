# Fix git 128 + Photographer patches (0005, 0008, 0009).
# Step 1: PATH so git.bat wrapper is used (avoids exit 128).
# Step 2: Disable 0005, 0008, 0009 so apply completes.
# Step 3: Get blob hashes, update patch files, re-enable patches.
# Step 4: Apply 0005/0008/0009 directly with "git apply" to paper-api and paper-server
#         (avoids second applyAllPatches which often fails with "corrupt patch").

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$apiPatchDir = Join-Path $root "folia-api\paper-patches\features"
$serverPaperPatchDir = Join-Path $root "folia-server\paper-patches\features"
$serverMcPatchDir = Join-Path $root "folia-server\minecraft-patches\features"
$patch5 = Join-Path $apiPatchDir "0005-Photographer-API.patch"
$bak5 = Join-Path $apiPatchDir "0005-Photographer-API.patch.bak"
$patch8 = Join-Path $serverPaperPatchDir "0008-Photographer-API.patch"
$bak8 = Join-Path $serverPaperPatchDir "0008-Photographer-API.patch.bak"
$patch9 = Join-Path $serverMcPatchDir "0009-Photographer-API.patch"
$bak9 = Join-Path $serverMcPatchDir "0009-Photographer-API.patch.bak"
$paperApi = Join-Path $root "paper-api"
$paperServer = Join-Path $root "paper-server"
$newBlob = "0000000000000000000000000000000000000001"

# Use wrapper git so "git config commit.gpgSign false" is skipped
$env:PATH = "$root;$env:PATH"

# Disable all three Photographer patches
if (Test-Path $patch5) { Copy-Item $patch5 $bak5 -Force; Remove-Item $patch5 -Force; Write-Host "[1a] 0005 disabled" }
if (Test-Path $patch8) { Copy-Item $patch8 $bak8 -Force; Remove-Item $patch8 -Force; Write-Host "[1b] 0008 disabled" }
if (Test-Path $patch9) { Copy-Item $patch9 $bak9 -Force; Remove-Item $patch9 -Force; Write-Host "[1c] 0009 disabled" }

# Run apply
Write-Host "[2] Running applyAllPatches..."
& (Join-Path $root "gradlew.bat") applyAllPatches --no-configuration-cache --no-daemon
if ($LASTEXITCODE -ne 0) {
    if (Test-Path $bak5) { Move-Item $bak5 $patch5 -Force }
    if (Test-Path $bak8) { Move-Item $bak8 $patch8 -Force }
    if (Test-Path $bak9) { Move-Item $bak9 $patch9 -Force }
    throw "applyAllPatches failed. Restored 0005, 0008, 0009."
}

# --- Fix 0005 (paper-api) ---
if (-not (Test-Path $paperApi)) { throw "paper-api not found" }
Push-Location $paperApi
$bukkitBlob = (git hash-object "src/main/java/org/bukkit/Bukkit.java").Trim()
$serverBlob = (git hash-object "src/main/java/org/bukkit/Server.java").Trim()
Pop-Location
Write-Host "[3] API blobs: Bukkit=$bukkitBlob Server=$serverBlob"

$content5 = Get-Content $bak5 -Raw -Encoding UTF8
$content5 = $content5 -replace "(diff --git a/src/main/java/org/bukkit/Bukkit\.java b/src/main/java/org/bukkit/Bukkit\.java)\r?\n(--- a/)", "`$1`nindex ${bukkitBlob}..${newBlob} 100644`n`$2"
$content5 = $content5 -replace "(diff --git a/src/main/java/org/bukkit/Server\.java b/src/main/java/org/bukkit/Server\.java)\r?\n(--- a/)", "`$1`nindex ${serverBlob}..${newBlob} 100644`n`$2"
$content5 = $content5 -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($patch5, $content5, [System.Text.UTF8Encoding]::new($false))
Remove-Item $bak5 -Force
Write-Host "[4a] 0005 updated and re-enabled"

# --- Fix 0008 (paper-server / craftbukkit) ---
$serverPaperFiles = @(
    "src/main/java/io/papermc/paper/plugin/manager/PaperEventManager.java",
    "src/main/java/org/bukkit/craftbukkit/CraftServer.java",
    "src/main/java/org/bukkit/craftbukkit/entity/CraftEntity.java",
    "src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java"
)
Push-Location $paperServer
$hashes8 = @{}
foreach ($f in $serverPaperFiles) {
    if (Test-Path $f) { $hashes8[$f] = (git hash-object $f).Trim() }
}
Pop-Location

$content8 = Get-Content $bak8 -Raw -Encoding UTF8
foreach ($f in $serverPaperFiles) {
    $hash = $hashes8[$f]
    if (-not $hash) { continue }
    $escaped = [regex]::Escape($f) -replace "/", "\/"
    $pattern = "(diff --git a/${escaped} b/${escaped})\r?\nindex [0-9a-f]+\.\.[0-9a-f]+ 100644\r?\n(--- a/)"
    $content8 = $content8 -replace $pattern, "`$1`nindex ${hash}..${newBlob} 100644`n`$2"
}
$content8 = $content8 -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($patch8, $content8, [System.Text.UTF8Encoding]::new($false))
Remove-Item $bak8 -Force
Write-Host "[4b] 0008 updated and re-enabled"

# --- Fix 0009 (folia-server src/minecraft - minecraft source lives here) ---
# Patch uses path "net/minecraft/..."; minecraft source is in folia-server/src/minecraft/java/net/minecraft/...
$mcPatchPaths = @(
    "net/minecraft/commands/CommandSourceStack.java",
    "net/minecraft/commands/arguments/selector/EntitySelector.java",
    "net/minecraft/server/MinecraftServer.java",
    "net/minecraft/server/PlayerAdvancements.java",
    "net/minecraft/server/commands/OpCommand.java",
    "net/minecraft/server/level/ServerLevel.java",
    "net/minecraft/server/players/PlayerList.java"
)
$mcDir = Join-Path $root "folia-server\src\minecraft\java"
if (-not (Test-Path $mcDir)) { throw "folia-server src/minecraft/java not found" }
Push-Location $mcDir
$hashes = @{}
foreach ($p in $mcPatchPaths) {
    if (Test-Path $p) { $hashes[$p] = (git hash-object $p).Trim() }
}
Pop-Location

$content9 = Get-Content $bak9 -Raw -Encoding UTF8
foreach ($p in $mcPatchPaths) {
    $hash = $hashes[$p]
    if (-not $hash) { continue }
    $escaped = [regex]::Escape($p) -replace "/", "\/"
    $pattern = "(diff --git a/${escaped} b/${escaped})\r?\nindex [0-9a-f]+\.\.[0-9a-f]+ 100644\r?\n(--- a/)"
    $replacement = "`$1`nindex ${hash}..${newBlob} 100644`n`$2"
    $content9 = $content9 -replace $pattern, $replacement
}
$content9 = $content9 -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($patch9, $content9, [System.Text.UTF8Encoding]::new($false))
Remove-Item $bak9 -Force
Write-Host "[4c] 0009 updated and re-enabled"

# Step 5: Apply the three patches with "git apply" (no second applyAllPatches)
# Mailbox format: strip header before first "diff --git", then apply as unified diff
function Apply-MailboxPatch {
    param([string]$PatchPath, [string]$WorkDir, [int]$Strip = 1)
    $full = [System.IO.File]::ReadAllText($PatchPath)
    $idx = $full.IndexOf("diff --git")
    if ($idx -lt 0) { throw "No diff in patch: $PatchPath" }
    $diffOnly = $full.Substring($idx)
    $diffOnly = $diffOnly -replace "`r`n", "`n"
    # Remove "index ..." lines so git apply uses context only
    $diffOnly = $diffOnly -replace "(?m)^index [0-9a-f]+\.\.[0-9a-f]+ 100644\r?\n", ""
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $diffOnly, [System.Text.UTF8Encoding]::new($false))
    try {
        Push-Location $WorkDir
        & git apply -p $Strip --ignore-whitespace $tmp
        if ($LASTEXITCODE -ne 0) { throw "git apply failed in $WorkDir for $PatchPath" }
    } finally {
        Pop-Location
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "[5] Applying 0005 to paper-api (git apply)..."
Apply-MailboxPatch -PatchPath $patch5 -WorkDir $paperApi -Strip 1
Write-Host "[6] Applying 0008 to paper-server (git apply)..."
Apply-MailboxPatch -PatchPath $patch8 -WorkDir $paperServer -Strip 1
Write-Host "[7] Applying 0009 to folia-server src/minecraft/java (git apply)..."
$mcDir = Join-Path $root "folia-server\src\minecraft\java"
if (-not (Test-Path $mcDir)) { throw "folia-server src/minecraft/java not found (run applyAllPatches first)" }
Apply-MailboxPatch -PatchPath $patch9 -WorkDir $mcDir -Strip 1

Write-Host "Done. Photographer patches applied. You can run: .\gradlew.bat build"
