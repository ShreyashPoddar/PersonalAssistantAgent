# READ-ONLY snapshot of WhatsApp Desktop's chat list (Windows UI Automation). Never clicks, types or opens a chat,
# so read receipts never change. Prints one JSON array of the rows' accessible text; hub/wa_reader.py parses it.
# Runs locally; the text goes only to the hub on this laptop.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$A = [System.Windows.Automation.AutomationElement]
$pane = $null
foreach ($w in $A::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)) {
    if ($w.Current.ClassName -eq 'Chrome_WidgetWin_1' -and $w.Current.Name -eq 'WhatsApp') { $pane = $w; break }
}
if (-not $pane) { '{"window": false, "rows": []}'; exit }
$rows = $pane.FindAll([System.Windows.Automation.TreeScope]::Descendants,
    (New-Object System.Windows.Automation.PropertyCondition($A::ControlTypeProperty, [System.Windows.Automation.ControlType]::DataItem)))
$out = @()
foreach ($r in $rows) {
    if ($r.Current.LocalizedControlType -eq 'row' -and $r.Current.Name) { $out += $r.Current.Name }
}
@{ window = $true; rows = $out } | ConvertTo-Json -Compress -Depth 3
