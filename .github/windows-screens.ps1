# The Windows installer tried as a person would: installed (just for you, quietly), opened, photographed, and the
# welcome stepped through with the keyboard (Tab to its word, Enter). Run from the workflow's windows job, in pwsh.
# EDITION (neon-coast) and EDITION_NAME (Neon Coast) from the job. Leaves shots/.
$ErrorActionPreference = 'Continue'
$name = $env:EDITION_NAME
New-Item -ItemType Directory -Force shots | Out-Null
$msi = (Resolve-Path "out\$($env:EDITION).msi").Path
$p = Start-Process msiexec.exe -ArgumentList "/i `"$msi`" /qn /norestart /l*v `"$PWD\shots\install-log.txt`"" -Wait -PassThru
"msiexec exit code: $($p.ExitCode)"
$where = @("$env:LOCALAPPDATA\$name", "$env:LOCALAPPDATA\Programs\$name", "$env:ProgramFiles\$name")
$exe = Get-ChildItem -Path $where -Filter "$name.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if ($exe) { "installed: $($exe.FullName)" } else {
  "not found installed in $($where -join ', '): the built app instead"
  $exe = Get-Item "ink-music\desktop\build\compose\binaries\main\app\$name\$name.exe"
}
Get-ChildItem "$env:APPDATA\Microsoft\Windows\Start Menu\Programs" -Recurse -Filter "*.lnk" | Where-Object { $_.Name -like "*$name*" } | ForEach-Object { "start menu: $($_.FullName)" }
$app = Start-Process $exe.FullName -PassThru
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
function Shot($n) {
  $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
  $bmp.Save("$PWD\shots\$n.png", [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose()
  "shot $n"
}
Start-Sleep 30
"running: $(-not $app.HasExited)"
Shot "win-01-open"
$ws = New-Object -ComObject WScript.Shell
for ($i = 2; $i -le 5; $i++) {
  $null = $ws.AppActivate($app.Id)
  Start-Sleep 1
  $ws.SendKeys("{TAB}"); Start-Sleep -Milliseconds 400; $ws.SendKeys("{ENTER}")
  Start-Sleep 6
  Shot ("win-0$i-step")
}
$crash = "$env:APPDATA\$name\crash.log"
if (Test-Path $crash) { Copy-Item $crash "shots\crash-log.txt"; "crash.log copied" }
Get-ChildItem "$env:APPDATA\$name" -ErrorAction SilentlyContinue | ForEach-Object { "its files: $($_.Name)" }
if (-not $app.HasExited) { Stop-Process -Id $app.Id -Force }
Get-ChildItem shots
