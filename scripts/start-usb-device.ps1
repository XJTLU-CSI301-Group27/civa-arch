param([string]$Serial, [switch]$CheckOnly)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$sdkCandidates=@($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,"$env:LOCALAPPDATA\Android\Sdk",'C:\Android Studio')
$localFile=Join-Path $project 'local.properties'
if(Test-Path -LiteralPath $localFile){
 $sdkLine=Get-Content -LiteralPath $localFile | Where-Object {$_ -match '^sdk\.dir='} | Select-Object -First 1
 if($sdkLine){$sdkCandidates=@(($sdkLine.Substring(8) -replace '\\:',':' -replace '\\\\','\'))+$sdkCandidates}
}
$adbCommand=Get-Command adb.exe -ErrorAction SilentlyContinue
$adbChoices=@();if($adbCommand){$adbChoices+= $adbCommand.Source}
$adbChoices+=@($sdkCandidates | Where-Object {$_} | ForEach-Object {Join-Path $_ 'platform-tools\adb.exe'})
$adbPath=$adbChoices | Where-Object {Test-Path -LiteralPath $_} | Select-Object -First 1
if(-not $adbPath){throw 'ADB not found. Install Android SDK Platform Tools or set ANDROID_HOME.'}
$connected=@(& $adbPath devices | ForEach-Object {if($_ -match '^(\S+)\s+device\s*$'){$matches[1]}})
if(-not $Serial){
 $physical=@($connected | Where-Object {$_ -notmatch '^emulator-'})
 if($physical.Count -ne 1){throw 'Connect one tablet and allow USB debugging, or specify -Serial.'}
 $Serial=$physical[0]
}
if($Serial -notin $connected){throw 'The selected device is not connected or has not authorized USB debugging.'}
$client=New-Object Net.Sockets.TcpClient
try{$client.Connect('127.0.0.1',8080)}catch{throw 'Start the project backend first (start-civa.bat, or your configured native launcher).'}finally{$client.Dispose()}
& $adbPath -s $Serial reverse tcp:8080 tcp:8080
if($LASTEXITCODE -ne 0){throw 'Could not configure USB backend forwarding.'}
Write-Host 'USB connection ready: device localhost:8080 -> computer backend:8080.'
if($CheckOnly){exit 0}
# Keep subsequent Android Studio Run builds on this machine pointed at USB localhost.
$localSettings=if(Test-Path -LiteralPath $localFile){[IO.File]::ReadAllText($localFile)}else{''}
$localSettings=[regex]::Replace($localSettings,'(?m)^civa\.debugApiBaseUrl=.*(?:\r?\n|$)','')
$localSettings=$localSettings.TrimEnd()+"`n"+'civa.debugApiBaseUrl=http://127.0.0.1:8080/'+"`n"
[IO.File]::WriteAllText($localFile,$localSettings,(New-Object Text.UTF8Encoding($false)))

$javaChoices=@($env:JAVA_HOME)
$javaChoices+=@(Get-ChildItem -Path "$env:USERPROFILE\.jdks\*17*","$env:ProgramFiles\Java\*17*","$env:ProgramFiles\Microsoft\*17*","$env:ProgramFiles\Eclipse Adoptium\*17*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
$jdk=$javaChoices | Where-Object {$_ -and (Test-Path -LiteralPath "$_\release") -and (Test-Path -LiteralPath "$_\bin\java.exe") -and (Select-String -LiteralPath "$_\release" -Pattern '^JAVA_VERSION="17[.\-"]' -Quiet)} | Select-Object -First 1
if(-not $jdk){throw 'Install JDK17 or set JAVA_HOME to its root folder.'}
$env:JAVA_HOME=$jdk
Push-Location -LiteralPath $project
try{
 & .\gradlew.bat '-PCIVA_API_BASE_URL=http://127.0.0.1:8080/' -PdevStackOff :app:assembleDebug --console=plain
 if($LASTEXITCODE -ne 0){throw 'USB app build failed; no app data was removed.'}
 & $adbPath -s $Serial install -r (Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk')
 if($LASTEXITCODE -ne 0){throw 'Update installation failed. Existing app was not uninstalled or cleared.'}
 & $adbPath -s $Serial shell am start -W -n edu.xjtlu.civa/edu.xjtlu.civa.MainActivity
 if($LASTEXITCODE -ne 0){throw 'Could not launch the app.'}
 Write-Host 'Tablet app started. Keep the USB cable and computer backend connected.'
}finally{Pop-Location}
