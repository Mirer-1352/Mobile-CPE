param([switch]$SkipCompile)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDir
try {
    if (-not $SkipCompile) {
        & (Join-Path $projectDir 'gradlew.bat') :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToRuntimeJar --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    }
    $cacheDir = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $modulesDir = Join-Path $cacheDir 'caches\modules-2\files-2.1'
    function Find-Jar([string]$relative, [string]$pattern) {
        $file = Get-ChildItem -LiteralPath (Join-Path $modulesDir $relative) -Recurse -File -Filter $pattern | Select-Object -First 1
        if (-not $file) { throw "Missing dependency: $relative" }
        return $file.FullName
    }
    $entries = @(
        (Join-Path $projectDir 'app\build\intermediates\built_in_kotlinc\debugUnitTest\compileDebugUnitTestKotlin\classes'),
        (Join-Path $projectDir 'app\build\intermediates\runtime_app_classes_jar\debug\bundleDebugClassesToRuntimeJar\classes.jar'),
        (Find-Jar 'junit\junit\4.13.2' 'junit-*.jar'),
        (Find-Jar 'org.hamcrest\hamcrest-core' '*.jar'),
        (Find-Jar 'org.jetbrains.kotlin\kotlin-stdlib\2.2.10' 'kotlin-stdlib-*.jar')
        (Find-Jar 'org.json\json\20240303' 'json-*.jar')
        (Find-Jar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.10.2' 'kotlinx-coroutines-core-jvm-*.jar')
    )
    $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java).Source }
    # Pass Unicode paths directly through CreateProcess, avoiding Gradle's worker @argfile encoding issue.
    $testClasses = Get-ChildItem -LiteralPath $entries[0] -Recurse -File -Filter '*Test.class' |
        Where-Object { -not $_.Name.Contains('$') } |
        ForEach-Object { $_.FullName.Substring($entries[0].Length + 1).Replace('\', '.').Replace('/', '.').Replace('.class', '') } |
        Sort-Object
    if (-not $testClasses) { throw 'No compiled test classes found' }
    & $javaBin -cp ($entries -join ';') org.junit.runner.JUnitCore @testClasses
    if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
} finally { Pop-Location }
