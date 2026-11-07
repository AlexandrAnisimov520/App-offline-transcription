param(
    [string]$AudioDirectory = 'C:\Users\Юлия Кротова\Downloads\Telegram Desktop',
    [string]$OutputDirectory = 'D:\Nuclear-hack-Bimboteam\speaker_candidate_20261107',
    [string]$JavaHome = 'C:\Users\Юлия Кротова\.jdks\jbr-21.0.11',
    [string]$Ffmpeg = 'C:\Users\Юлия Кротова\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.2-full_build\bin\ffmpeg.exe'
)
$ErrorActionPreference = 'Stop'
$ortJar = 'D:\Gradle\caches\8.10.2\transforms\87dd14e6c147e48c4f6a63afceb21ce7\transformed\onnxruntime-android-1.24.3-api.jar'
$androidJar = 'D:\Androidhack\platforms\android-35\android.jar'
foreach ($required in @($Ffmpeg, "$JavaHome\bin\javac.exe", $ortJar, $androidJar)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing dependency: $required" }
}
& python -X utf8 "$PSScriptRoot\audit_user_speakers.py" --audio-dir $AudioDirectory --output $OutputDirectory --ffmpeg $Ffmpeg --java-home $JavaHome --android-jar $androidJar --ort-java $ortJar --normalize-quiet-vad --baseline-only --counts "$PSScriptRoot/user_speaker_counts.json" --names clean_01 clean_02 clean_03 clean_04 dialogue_01 dialogue_02 dialogue_03 dialogue_04 terms_02 terms_03 noise_01 noise_02 noise_03 noise_04 speed_01 speed_02 quiet_02 diarization_01 diarization_02 diarization_03 diarization_04
if ($LASTEXITCODE -ne 0) { throw "Speaker audit failed: $LASTEXITCODE" }
