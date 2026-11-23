# Lumio: what is playing right now (Spotify, browser, any player that shows in the Windows media overlay).
# Started by Lumio while Minecraft runs; it only reads the Windows media session and ends with Minecraft.
# Arguments: Minecraft's process id, the command file (pause/next/previous), the file for the cover.
param([int]$GamePid, [string]$CmdFile, [string]$ArtFile)
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$type) {
    $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $null = $t.Wait(3000)
    if ($t.IsCompleted) { return $t.Result }
    return $null
}
function B64([string]$s) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$s")) }
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$lastKey = ''
$artN = 0
while ($true) {
    if (-not (Get-Process -Id $GamePid -ErrorAction SilentlyContinue)) { exit }
    try {
        $s = $null
        if ($mgr) { $s = $mgr.GetCurrentSession() }
        if (Test-Path $CmdFile) {
            $c = "$(Get-Content $CmdFile -Raw)".Trim()
            Remove-Item $CmdFile -Force
            if ($s) {
                if ($c -eq 'toggle') { $null = $s.TryTogglePlayPauseAsync() }
                elseif ($c -eq 'next') { $null = $s.TrySkipNextAsync() }
                elseif ($c -eq 'previous') { $null = $s.TrySkipPreviousAsync() }
            }
        }
        if (-not $s) {
            [Console]::Out.WriteLine('-')
        } else {
            $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $i = $s.GetPlaybackInfo()
            $t = $s.GetTimelineProperties()
            $key = "$($p.Title)|$($p.Artist)"
            if ($key -ne $lastKey) {
                $lastKey = $key
                $artN++
                try {
                    Remove-Item $ArtFile -Force -ErrorAction SilentlyContinue
                    if ($p.Thumbnail) {
                        $st = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                        $in = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($st)
                        $out = [System.IO.File]::Create($ArtFile)
                        $in.CopyTo($out)
                        $out.Close()
                        $in.Close()
                    }
                } catch { }
            }
            $playing = if ("$($i.PlaybackStatus)" -eq 'Playing') { 1 } else { 0 }
            $pos = [long]$t.Position.TotalMilliseconds
            $len = [long]($t.EndTime - $t.StartTime).TotalMilliseconds
            $upd = $t.LastUpdatedTime.ToUnixTimeMilliseconds()
            [Console]::Out.WriteLine("T`t$(B64 $p.Title)`t$(B64 $p.Artist)`t$playing`t$pos`t$len`t$upd`t$(B64 $s.SourceAppUserModelId)`t$artN")
        }
        [Console]::Out.Flush()
    } catch {
        [Console]::Out.WriteLine('-')
        [Console]::Out.Flush()
    }
    Start-Sleep -Milliseconds 700
}
