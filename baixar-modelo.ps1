param([string]$Destino = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$raiz = [IO.Path]::GetFullPath($Destino)
New-Item -ItemType Directory -Force -Path $raiz | Out-Null
$temporario = Join-Path $raiz ('.download-voz-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporario | Out-Null

function Baixar-Verificado([string]$Url, [string]$Arquivo, [string]$Sha256) {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Arquivo
    if ((Get-FileHash -LiteralPath $Arquivo -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Hash incorreto no download: $([IO.Path]::GetFileName($Arquivo))"
    }
}
try {
    $zip = Join-Path $temporario 'whisper.zip'
    Baixar-Verificado 'https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.3/whisper-bin-x64.zip' $zip 'd824b1e37599f882b396e73f1ee0bfd5d0529f700314c48311dcbd00b803321d'
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $temporario 'bin')
    $cli = Get-ChildItem -LiteralPath (Join-Path $temporario 'bin') -Recurse -Filter 'whisper-cli.exe' | Select-Object -First 1
    if (-not $cli) { throw 'O pacote nao contem whisper-cli.exe' }
    $ferramentas = Join-Path $raiz 'ferramentas/whisper'
    New-Item -ItemType Directory -Force -Path $ferramentas | Out-Null
    Get-ChildItem -LiteralPath $cli.DirectoryName -File | Copy-Item -Destination $ferramentas -Force

    $modelos = Join-Path $raiz 'modelos'
    New-Item -ItemType Directory -Force -Path $modelos | Out-Null
    $modelo = Join-Path $modelos 'ggml-base.bin'
    $hashModelo = '60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe'
    if (-not (Test-Path -LiteralPath $modelo) -or (Get-FileHash -LiteralPath $modelo -Algorithm SHA256).Hash -ne $hashModelo) {
        $download = Join-Path $temporario 'ggml-base.bin'
        Baixar-Verificado 'https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin' $download $hashModelo
        Move-Item -LiteralPath $download -Destination $modelo -Force
    }
    Write-Host 'Whisper.cpp v1.8.3 e modelo base multilingue instalados e verificados.'
} finally {
    $resolvido = [IO.Path]::GetFullPath($temporario)
    if ($resolvido.StartsWith($raiz.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($resolvido).StartsWith('.download-voz-')) {
        Remove-Item -LiteralPath $resolvido -Recurse -Force
    }
}
