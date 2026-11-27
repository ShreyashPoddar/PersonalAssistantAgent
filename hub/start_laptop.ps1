# Start PAA's laptop model + hub (Windows). Everything stays on this laptop and your home network.
#   powershell -ExecutionPolicy Bypass -File hub\start_laptop.ps1 [-Lora <adapter.gguf>]
# Model server: llama.cpp on 127.0.0.1:8081 only (not reachable from the network). Hub: HTTPS on <tailscale ip>:8765 with the
# pairing token + pinned certificate; it is the only thing the phone talks to.
param(
    [string]$LlamaDir = "$env:USERPROFILE\paa-llm\llama.cpp",
    [string]$Model = "$env:USERPROFILE\paa-llm\models\gemma-4-12b-it-qat-q4_0.gguf",
    [string]$Lora = ""
)
$serverArgs = @("-m", $Model, "--host", "127.0.0.1", "--port", "8081", "-ngl", "99", "-c", "8192", "--no-webui",
          "--jinja", "--reasoning-budget", "0", "--chat-template-kwargs", '{"enable_thinking": false}')
$mode = "rules"
if ($Lora) { $serverArgs += @("--lora", $Lora); $mode = "student" }   # fine-tuned: PAA's own prompt, no rules needed
$log = "$env:USERPROFILE\paa-llm\server.log"
Start-Process -FilePath "$LlamaDir\llama-server.exe" -ArgumentList $serverArgs -WindowStyle Hidden -RedirectStandardError $log
Write-Host "model server starting (log: $log) ..."
do { Start-Sleep -Seconds 2; $ok = try { (Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8081/health).Content -match "ok" } catch { $false } } until ($ok)
Write-Host "model ready; starting hub (prompt mode: $mode)"
# --wa: also read WhatsApp Desktop's chat list (read-only; keep WhatsApp open or minimised, not closed)
python "$PSScriptRoot\server.py" --llm http://127.0.0.1:8081 --llm-prompt $mode --wa
