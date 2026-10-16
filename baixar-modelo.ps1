# Baixa o modelo de português do Vosk, usado pelo gatilho "Jarvis, iniciar".
#
# São ~45 MB que não entram no repositório. Sem eles o agente continua funcionando: avisa no log
# e segue só com as palmas.
#
# Uso:  .\baixar-modelo.ps1

$ErrorActionPreference = 'Stop'

$modelo  = 'vosk-model-small-pt-0.3'
$url     = "https://alphacephei.com/vosk/models/$modelo.zip"
$pasta   = Join-Path $PSScriptRoot 'modelos'
$destino = Join-Path $pasta $modelo
$zip     = Join-Path $env:TEMP "$modelo.zip"

function Test-Modelo($caminho) {
    return ((Test-Path -LiteralPath (Join-Path $caminho 'am\final.mdl') -PathType Leaf) -or
            (Test-Path -LiteralPath (Join-Path $caminho 'final.mdl') -PathType Leaf))
}

if (Test-Modelo $destino) {
    Write-Host "O modelo ja esta em $destino" -ForegroundColor Green
    Write-Host 'Para rebaixar, apague essa pasta e rode de novo.'
    exit 0
}

New-Item -ItemType Directory -Force -Path $pasta | Out-Null

Write-Host "Baixando $modelo (~45 MB)..." -ForegroundColor Cyan
# O Invoke-WebRequest com barra de progresso fica MUITO lento em arquivo grande no PowerShell 5.1.
$progresso = $ProgressPreference
$ProgressPreference = 'SilentlyContinue'
try {
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
} finally {
    $ProgressPreference = $progresso
}

Write-Host 'Extraindo...' -ForegroundColor Cyan
Expand-Archive -Path $zip -DestinationPath $pasta -Force
Remove-Item $zip -Force

# O agente aceita am\final.mdl e final.mdl na raiz (como no modelo de portugues 0.3).
# Trocar de modelo nao pede mexer no config.properties.
if (-not (Test-Modelo $destino)) {
    Write-Host 'O zip veio sem am\final.mdl ou final.mdl; o modelo pode ter mudado de formato.' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host "Pronto: $destino" -ForegroundColor Green
Write-Host 'Reinicie o agente. O log deve dizer: "gatilho de voz de pe com o modelo ..."'
