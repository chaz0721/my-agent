# Performance baseline (M7 7D) with the fake provider: no gate, only numbers (docs/reports/bench-M7.md).
# Usage: powershell -NoProfile -File build\bench.ps1 [-Runs 5]
param([int]$Runs = 5)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("pai-bench-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
  mvn -B -ntp -q package -DskipTests
  if ($LASTEXITCODE -ne 0) { throw "package failed" }
  mvn -B -ntp -q -pl pai-cli -am test "-Dtest=BenchTest" "-Dsurefire.failIfNoSpecifiedTests=false" `
    "-Dpai.bench=1" "-Dpai.bench.home=$tmp" "-Dpai.bench.out=$tmp\bench.txt" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "bench test failed" }
  $jar = "pai-cli\target\pai-cli.jar"
  $bench = (Get-ChildItem -Directory -Path $tmp -Filter "pai-bench-*" | Select-Object -First 1).FullName
  New-Item -ItemType Directory -Path "$tmp\cold", "$tmp\coldcwd" | Out-Null
  function StartMs([string]$PaiHome, [string]$Cwd, [string[]]$Extra) {
    $env:PAI_HOME = $PaiHome
    $ms = (Measure-Command {
      "/quit" | & java --enable-native-access=ALL-UNNAMED -jar $jar --provider fake --cwd $Cwd @Extra 2>&1 | Out-Null
    }).TotalMilliseconds
    Remove-Item Env:PAI_HOME
    return [math]::Round($ms)
  }
  function Median([double[]]$xs) { $s = $xs | Sort-Object; return $s[[int][math]::Floor(($s.Count - 1) / 2)] }
  $cold = Median (1..$Runs | ForEach-Object { StartMs "$tmp\cold" "$tmp\coldcwd" @() })
  $resume = Median (1..$Runs | ForEach-Object { StartMs "$bench\home" "$bench\proj" @("--resume") })
  Write-Output "BENCH|cold-start-to-quit|$cold|ms|java -jar pai-cli.jar --provider fake, stdin '/quit', median of $Runs"
  Write-Output "BENCH|resume-1000-entries-to-quit|$resume|ms|same with --resume of a 1000-entry session, median of $Runs"
  Get-Content "$tmp\bench.txt"
} finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
