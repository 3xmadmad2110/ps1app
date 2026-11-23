# Downloads the official SwanStation (DuckStation-based) libretro nightly core
# for Android and places it where Gradle packages native libraries.
$ErrorActionPreference = 'Stop'

$base = 'https://buildbot.libretro.com/nightly/android/latest'
$targetRoot = Join-Path $PSScriptRoot '..\android\app\src\main\jniLibs'
$abis = @('arm64-v8a', 'armeabi-v7a')

foreach ($abi in $abis) {
    $url = "$base/$abi/swanstation_libretro_android.so.zip"
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) "swanstation-$abi.zip"
    Write-Host "Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing

    $target = Join-Path $targetRoot $abi
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    if (Test-Path $tmp) {
        Expand-Archive -Path $tmp -DestinationPath $target -Force
        Get-ChildItem -Path $target -Filter '*.so' | ForEach-Object {
            $destination = Join-Path $target 'libswanstation.so'
            if ($_.FullName -ne $destination) {
                if (Test-Path $destination) { Remove-Item $destination -Force }
                Rename-Item -Path $_.FullName -NewName 'libswanstation.so'
            }
        }
        Remove-Item $tmp -Force
    }
    Write-Host "Core ready: $target\libswanstation.so"
}
