# SWE-bench Lite evaluation driver (Windows host). Loads the DeepSeek key from D:\PaiCLI\pai-next\.env into this
# process's environment only (never printed or logged), prepares the venv and calls build/eval/swebench.py.
# Usage:
#   powershell -NoProfile -File build\eval\run.ps1 sample
#   powershell -NoProfile -File build\eval\run.ps1 run --workers 2 [--only <id>] [--groups bare,full]
#   powershell -NoProfile -File build\eval\run.ps1 preds | evaluate | report
# The harness image (pai-swebench-harness:4.1.0) is built from build\eval\harness.Dockerfile on first use.
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$work = "D:\PaiCLI\tmp\eval"
$venv = "$work\venv"
foreach ($line in Get-Content "$repo\.env") {
  if ($line -match '^\s*(PAI_OPENAI_COMPAT_API_KEY|PAI_OPENAI_COMPAT_BASE_URL)\s*=\s*(.*)$') {
    [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"'), "Process")
  }
}
if (-not (Test-Path "$venv\Scripts\python.exe")) {
  python -m venv $venv
  & "$venv\Scripts\python.exe" -m pip install -q "datasets"
}
$env:HF_HOME = "$work\hf"
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
if ($args[0] -eq "evaluate") {
  docker image inspect pai-swebench-harness:4.1.0 *> $null
  if ($LASTEXITCODE -ne 0) {
    docker build -q -t pai-swebench-harness:4.1.0 -f "$PSScriptRoot\harness.Dockerfile" $PSScriptRoot
  }
}
& "$venv\Scripts\python.exe" "$PSScriptRoot\swebench.py" --work $work @args
$code = $LASTEXITCODE
# key hygiene: no key-shaped string in anything this run wrote
$bad = Get-ChildItem -Path "$work\runs", "$work\home", "$work\preds", "$work\harness" -Recurse -File -Include *.log, *.txt, *.json, *.jsonl, *.diff -ErrorAction SilentlyContinue |
  Select-String -Pattern 'sk-[A-Za-z0-9]{16,}|Authorization:\s*Bearer\s+[A-Za-z0-9]' | Measure-Object
Write-Output ("key-like strings in eval outputs: " + $bad.Count)
exit $code
