<#
    Registra o agente do Jarvis no Agendador de Tarefas, para iniciar no logon deste usuário.

    Por que logon e não serviço do Windows: o agente precisa da sessão interativa. O Robot da
    captura de tela não vê o desktop de outra sessão, o LockWorkStation age na sessão de quem
    chama, e o Chrome em modo kiosk precisa de um desktop para abrir. Como serviço, três das dez
    ações simplesmente não funcionariam.

    Roda SEM elevação de propósito (-RunLevel Limited): desligar, reiniciar e bloquear a própria
    máquina não pedem administrador. Menor privilégio.

    Uso:
        .\instalar.ps1
        .\instalar.ps1 -Jar .\target\jarvis-agente-pc.jar -Config .\config.properties

    Para remover:
        Unregister-ScheduledTask -TaskName JarvisAgentePc -Confirm:$false
#>
[CmdletBinding()]
param(
    [string]$Jar = (Join-Path $PSScriptRoot 'target\jarvis-agente-pc.jar'),
    [string]$Config = (Join-Path $PSScriptRoot 'config.properties'),
    [string]$Tarefa = 'JarvisAgentePc'
)

$ErrorActionPreference = 'Stop'

function Falhar($mensagem) {
    Write-Host "ERRO: $mensagem" -ForegroundColor Red
    exit 1
}

# ---- 1. O jar ----
if (-not (Test-Path $Jar)) {
    Falhar "não achei o jar em $Jar. Compile antes com: mvn package"
}
$Jar = (Resolve-Path $Jar).Path

# ---- 2. O config.properties, com o token preenchido ----
if (-not (Test-Path $Config)) {
    Falhar "não achei $Config. Copie o config.properties.example e preencha."
}
$Config = (Resolve-Path $Config).Path

$token = (Get-Content $Config | Where-Object { $_ -match '^\s*pcToken\s*=' }) -replace '^\s*pcToken\s*=\s*', ''
if ([string]::IsNullOrWhiteSpace($token)) {
    Falhar "pcToken está vazio em $Config. Ponha o mesmo PC_TOKEN que está no .env da VPS."
}

# ---- 3. O javaw.exe (sem janela de console) ----
$javaw = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javaw.exe'))) {
    $javaw = (Join-Path $env:JAVA_HOME 'bin\javaw.exe')
} else {
    $achado = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($achado) { $javaw = $achado.Source }
}
if (-not $javaw) {
    Falhar "não achei o javaw.exe. Instale o Java 21 ou defina JAVA_HOME."
}

Write-Host "jar:    $Jar"
Write-Host "config: $Config"
Write-Host "java:   $javaw"

# ---- 4. A tarefa ----
$usuario = "$env:USERDOMAIN\$env:USERNAME"

$acao = New-ScheduledTaskAction -Execute $javaw `
    -Argument "-jar `"$Jar`" `"$Config`"" `
    -WorkingDirectory (Split-Path $Jar -Parent)

$gatilho = New-ScheduledTaskTrigger -AtLogOn -User $usuario

# MultipleInstances IgnoreNew é o que impede duas cópias do agente consumindo a mesma fila.
# ExecutionTimeLimit 0 porque ele roda o dia inteiro, e o reinício de 1 min cobre uma queda.
$ajustes = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit 0 `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable

$quem = New-ScheduledTaskPrincipal -UserId $usuario -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $Tarefa -Action $acao -Trigger $gatilho `
    -Settings $ajustes -Principal $quem `
    -Description 'Agente do Jarvis: busca comandos na VPS e executa neste PC.' `
    -Force | Out-Null

Start-ScheduledTask -TaskName $Tarefa

Write-Host ''
Write-Host "Tarefa '$Tarefa' registrada e iniciada." -ForegroundColor Green
Write-Host "Log:     $env:LOCALAPPDATA\jarvis-agente-pc\agente.log"
Write-Host "Estado:  Get-ScheduledTask -TaskName $Tarefa"
Write-Host "Parar:   Stop-ScheduledTask -TaskName $Tarefa"
Write-Host "Remover: Unregister-ScheduledTask -TaskName $Tarefa -Confirm:`$false"
Write-Host ''
Write-Host "Teste pelo Telegram: 'status do PC'." -ForegroundColor Cyan
