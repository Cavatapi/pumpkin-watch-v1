param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$jdk = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $env:JAVA_HOME } else { Get-ChildItem -LiteralPath "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path "$($_.FullName)\bin\javac.exe" } | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName }
if (!$jdk) { throw 'Set JAVA_HOME to a JDK 21 or newer, or run watch.PumpkinWatch in IntelliJ.' }
New-Item -ItemType Directory -Force -Path target\classes | Out-Null
$sources = @(Get-ChildItem src\main\java -Recurse -Filter *.java | ForEach-Object FullName)
if ($Test) { $sources += @(Get-ChildItem src\test\java -Recurse -Filter *.java | ForEach-Object FullName) }
& "$jdk\bin\javac.exe" --release 21 -encoding UTF-8 -d target\classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
Copy-Item src\main\resources\* target\classes -Recurse -Force
if ($Test) { & "$jdk\bin\java.exe" -ea -cp target\classes watch.GameTest } else { & "$jdk\bin\java.exe" -cp target\classes watch.PumpkinWatch }
exit $LASTEXITCODE
