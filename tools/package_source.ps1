# Assembles dist/LumoBrowser-v<version>-source.zip from an explicit allow-list.
#
# The list is deliberate: the repository root also carries local build logs, scratch scripts and the
# Android build directories, and "zip everything" would ship all of them. What goes in the archive is
# exactly what a reader needs to open the project in Android Studio, add a local.properties, and
# build — plus the throwaway keystore, so a rebuild of the release variant can still update the APK
# that was delivered next to it.
#
# Usage:  pwsh -File tools/package_source.ps1 -Version 1.07

param(
    [string]$Version = '1.07',
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$include = @(
    'app/src',
    'app/schemas',
    'app/build.gradle.kts',
    'app/proguard-rules.pro',
    'build.gradle.kts',
    'settings.gradle.kts',
    'gradle.properties',
    'gradle',
    'gradlew',
    'gradlew.bat',
    'docs',
    'tools',
    'keystore',
    'README.md',
    'CHANGELOG.md',
    '.gitignore'
)

$distDir = Join-Path $Root 'dist'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$zip = Join-Path $distDir "LumoBrowser-v$Version-source.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }

$archive = [System.IO.Compression.ZipFile]::Open($zip, 'Create')
try {
    foreach ($item in $include) {
        $path = Join-Path $Root $item
        if (-not (Test-Path $path)) {
            Write-Warning "not found, skipped: $item"
            continue
        }
        if (Test-Path $path -PathType Container) {
            $files = Get-ChildItem $path -Recurse -File
            foreach ($file in $files) {
                $relative = $file.FullName.Substring($Root.Length + 1).Replace('\', '/')
                [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $archive, $file.FullName, "LumoBrowser/$relative",
                    [System.IO.Compression.CompressionLevel]::Optimal)
            }
        } else {
            $relative = $item.Replace('\', '/')
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $path, "LumoBrowser/$relative",
                [System.IO.Compression.CompressionLevel]::Optimal)
        }
    }
} finally {
    $archive.Dispose()
}

$size = (Get-Item $zip).Length
$reader = [System.IO.Compression.ZipFile]::OpenRead($zip)
try {
    $count = $reader.Entries.Count
} finally {
    $reader.Dispose()
}
Write-Host "wrote $zip ($size bytes, $count entries)"
