param(
    [Parameter(Mandatory=$true)][string]$Instances,
    [Parameter(Mandatory=$true)][string]$BaselineJar,
    [Parameter(Mandatory=$true)][string]$UnifiedJar,
    [Parameter(Mandatory=$true)][string]$EnhanceJar,
    [Parameter(Mandatory=$true)][string]$HarnessJar,
    [string]$Java='java',
    [long[]]$Seeds=@(789123,1,42,20241002,987654321),
    [ValidateSet('unified105','unified110','enhance160')][string[]]$EngineNames=@('unified105','unified110','enhance160'),
    [ValidateRange(1,1000)][int]$Repeats=5,
    [switch]$WarmOnly,
    [ValidateRange(1,1000)][int]$Prewarm=10,
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$RunName='results'
)
$ErrorActionPreference='Stop'
$taskBase=[IO.Path]::GetFullPath($Instances)
$inputs=@{unified105=$BaselineJar;unified110=$UnifiedJar;enhance160=$EnhanceJar}
$output=Join-Path $taskBase $RunName
[IO.Directory]::CreateDirectory($output)|Out-Null
$harness=[IO.Path]::GetFullPath($HarnessJar)
$engines=$EngineNames
for($seedIndex=0;$seedIndex -lt $Seeds.Length;$seedIndex++) {
    $seed=$Seeds[$seedIndex]
    for($pass=0;$pass -lt $engines.Length;$pass++) {
        $engine=$engines[($pass+$seedIndex)%$engines.Length]
        $instance=[IO.Path]::GetFullPath((Join-Path $taskBase $engine))
        if(-not $instance.StartsWith($taskBase+[IO.Path]::DirectorySeparatorChar)){throw 'Invalid instance path'}
        if(-not(Test-Path -LiteralPath (Join-Path $instance 'libraries'))){throw "Forge instance missing: $instance"}
        $mods=Join-Path $instance 'mods'
        [IO.Directory]::CreateDirectory($mods)|Out-Null
        # 每个被测实例仅安装一个指南针和相同中立工具；操作范围限于显式传入实例。
        foreach($file in Get-ChildItem -LiteralPath $mods -File){
            if($file.Name -in @('compass.jar','compassbench.jar')){Remove-Item -LiteralPath $file.FullName}
            else{throw "Unexpected mod in comparison instance: $($file.Name)"}
        }
        Copy-Item -LiteralPath ([IO.Path]::GetFullPath($inputs[$engine])) -Destination (Join-Path $mods 'compass.jar')
        Copy-Item -LiteralPath $harness -Destination (Join-Path $mods 'compassbench.jar')
        $benchWorlds=[IO.Path]::GetFullPath((Join-Path $instance 'benchmark-worlds'))
        if(Test-Path -LiteralPath $benchWorlds){
            $archive=[IO.Path]::GetFullPath((Join-Path $instance ('benchmark-worlds-'+[guid]::NewGuid().ToString('N'))))
            if(-not $benchWorlds.StartsWith($instance+[IO.Path]::DirectorySeparatorChar) -or -not $archive.StartsWith($instance+[IO.Path]::DirectorySeparatorChar)){throw 'Unsafe archive target'}
            Move-Item -LiteralPath $benchWorlds -Destination $archive
        }
        $properties=Join-Path $instance 'server.properties'
        $text=[IO.File]::ReadAllText($properties)
        foreach($entry in @{ 'level-seed'="$seed"; 'level-name'="world-$seed"; 'online-mode'='false'; 'server-ip'='127.0.0.1'; 'server-port'='25610'; 'max-tick-time'='-1'; 'view-distance'='5'; 'simulation-distance'='5' }.GetEnumerator()){
            if($text -match "(?m)^$($entry.Key)="){$text=[regex]::Replace($text,"(?m)^$($entry.Key)=.*$","$($entry.Key)=$($entry.Value)")}
            else{$text+="`n$($entry.Key)=$($entry.Value)"}
        }
        [IO.File]::WriteAllText($properties,$text)
        $metadata=@{seed=$seed;engine=$engine;java=$Java;radius=40000;structureSamples=25000000;biomeSamples=8000000;mainBudgetMs=2;
            searchThreads=4;startupSettlingTicks=60;maxHeap='3G';forge='47.4.18';origin=@(0,64,0);
            processor=[Environment]::GetEnvironmentVariable('PROCESSOR_IDENTIFIER');logicalProcessors=[Environment]::ProcessorCount;
            repeats=$Repeats;warmOnly=$WarmOnly.IsPresent;prewarm=if($WarmOnly){$Prewarm}else{0};
            compassSha256=(Get-FileHash -LiteralPath (Join-Path $mods 'compass.jar')).Hash;
            harnessSha256=(Get-FileHash -LiteralPath (Join-Path $mods 'compassbench.jar')).Hash}
        $metadata|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $output "$engine-$seed.config.json")
        Push-Location -LiteralPath $instance
        try {
            Write-Output "Running $engine seed $seed"
            & $Java '-Xmx3G' '-Dcompassbench.enabled=true' "-Dcompassbench.engine=$engine" "-Dcompassbench.repeats=$Repeats" "-Dcompassbench.warmOnly=$($WarmOnly.IsPresent.ToString().ToLowerInvariant())" "-Dcompassbench.prewarm=$Prewarm" '@libraries/net/minecraftforge/forge/1.20.1-47.4.18/win_args.txt' nogui *> (Join-Path $output "$engine-$seed.launch.log")
            Copy-Item -LiteralPath 'comparison.csv' -Destination (Join-Path $output "$engine-$seed.csv")
            Copy-Item -LiteralPath 'comparison-status.txt' -Destination (Join-Path $output "$engine-$seed.status.txt")
            if($LASTEXITCODE -ne 0 -or ([IO.File]::ReadAllText((Join-Path $instance 'comparison-status.txt'))) -notlike 'COMPLETE:*'){throw "Comparison failed: $engine $seed"}
        } finally {Pop-Location}
    }
}
$perTarget=if($WarmOnly){$Repeats+$Prewarm}else{$Repeats*2}
Write-Output "Completed $($Seeds.Length*$engines.Length*5*$perTarget) requests. Raw evidence: $output"
