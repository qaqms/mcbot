Get-CimInstance Win32_Process -Filter "name='java.exe'" | ForEach-Object {
  $cl = $_.CommandLine
  $tag = "OTHER"
  if ($cl -match "GradleDaemon") { $tag = "GRADLE-DAEMON" }
  elseif ($cl -match "KotlinCompileDaemon") { $tag = "KOTLIN" }
  elseif ($cl -match "LoomServer|devlaunchinjector|net\.minecraft|fabricmc") { $tag = "MC-RUN" }
  "{0} PID={1}" -f $tag, $_.ProcessId
}
