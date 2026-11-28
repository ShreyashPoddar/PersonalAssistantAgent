# Start PAA's laptop model + hub (Windows). Everything stays on this laptop and your own Tailscale devices.
#   powershell -ExecutionPolicy Bypass -File hub\start_laptop.ps1 [-Lora <adapter.gguf> | -NoLora]
# Model server: llama.cpp on 127.0.0.1:8081 only (not reachable from the network). Hub: HTTPS on <tailscale ip>:8765
# with the pairing token + pinned certificate; it is the only thing the phone talks to.
# By default the newest PAA-trained adapter in %USERPROFILE%\paa-llm\lora\ is used (Gemma 4 12B + LoRA).
param(
    [string]$LlamaDir = "$env:USERPROFILE\paa-llm\llama.cpp",
    [string]$Model = "$env:USERPROFILE\paa-llm\models\gemma-4-12b-it-qat-q4_0.gguf",
    [string]$Lora = "",
    [switch]$NoLora
)
if (-not $Lora -and -not $NoLora) {
    $newest = Get-ChildItem "$env:USERPROFILE\paa-llm\lora" -Recurse -Filter paa-lora.gguf -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($newest) { $Lora = $newest.FullName }
}
$serverArgs = @("-m", $Model, "--host", "127.0.0.1", "--port", "8081", "-ngl", "99", "-c", "8192", "--no-webui",
          "--jinja", "--reasoning-budget", "0")
# JSON via the environment: Start-Process re-splits arguments with spaces/quotes (the server then exits at once)
$env:LLAMA_ARG_CHAT_TEMPLATE_KWARGS = '{"enable_thinking":false}'
$mode = "rules"
if ($Lora) { $serverArgs += @("--lora", $Lora); $mode = "student" }   # fine-tuned: PAA's own prompt, no rules needed
New-Item -ItemType Directory -Force "$env:USERPROFILE\.paa-hub" | Out-Null
Set-Content -Encoding ascii "$env:USERPROFILE\.paa-hub\llm_mode" $mode   # the hub reads this on every request
Get-Process llama-server -ErrorAction SilentlyContinue | Stop-Process -Force   # one model server at a time (12 GB GPU)
Start-Sleep -Seconds 2
$log = "$env:USERPROFILE\paa-llm\server.log"
$proc = Start-Process -FilePath "$LlamaDir\llama-server.exe" -ArgumentList $serverArgs -WindowStyle Hidden -RedirectStandardError $log -PassThru
Write-Host "model server starting ($(if ($Lora) { 'Gemma 4 12B + ' + (Split-Path -Leaf (Split-Path $Lora)) } else { 'Gemma 4 12B, untrained' })) ..."
do {
    Start-Sleep -Seconds 2
    if ($proc.HasExited) { Write-Host "model server stopped (exit $($proc.ExitCode)); last lines of $log :"; Get-Content $log -Tail 15; exit 1 }
    $ok = try { (Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8081/health).Content -match "ok" } catch { $false }
} until ($ok)
Write-Host "model ready; starting hub (prompt mode: $mode)"
# --wa: also read WhatsApp Desktop's chat list (read-only; keep WhatsApp open or minimised, not closed)
python "$PSScriptRoot\server.py" --llm http://127.0.0.1:8081 --llm-prompt $mode --wa
