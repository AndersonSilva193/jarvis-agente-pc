# jarvis-agente-pc

O braço do [Jarvis](../jarvis) neste PC Windows. Ele pergunta à VPS se tem comando, executa e
devolve o resultado — abrir app, abrir página, abrir a HUD, bloquear a tela, mexer no volume,
tirar print, dizer como o PC está, desligar, reiniciar e cancelar o desligamento.

**Nada escuta porta nesta máquina.** Toda conexão é de dentro para fora (long polling em
`GET /api/pc/comandos`), como o próprio Jarvis faz com a API do Telegram. Sem porta aberta, sem
domínio, sem webhook.

Java 21, sem Spring, um jar só. A única dependência é o Jackson 3, o mesmo do Jarvis.

## Compilar

```powershell
mvn package
# target\jarvis-agente-pc.jar
```

## Configurar

```powershell
Copy-Item config.properties.example config.properties
notepad config.properties
```

Preencha:

- `urlApi` — onde o Jarvis atende (só https).
- `pcToken` — **o mesmo valor do `PC_TOKEN` no `.env` da VPS**. Gere com `openssl rand -hex 32`.
- `urlHud` — a tela do Jarvis, que o `abrir_hud` abre no Chrome em modo kiosk.
- `app.<nome>=<caminho do .exe>` — um por programa liberado.

O `config.properties` tem o token e está no `.gitignore`. Mantenha assim.

## Rodar na mão (para testar)

```powershell
java -jar target\jarvis-agente-pc.jar config.properties
```

A partida mostra a URL e os apps liberados. Daí, pelo Telegram: *"status do PC"*.

### Autoteste, sem falar com a VPS

```powershell
java -jar target\jarvis-agente-pc.jar config.properties --testar
```

Mede o PC, tira um print em `%LOCALAPPDATA%\jarvis-agente-pc\autoteste.png` e lista os apps que o
Jarvis vai conhecer — só as ações inofensivas, nada de desligar nem bloquear. É o primeiro passo
quando algo não funciona: se a captura falhar aqui, o agente não está na sessão interativa do
usuário.

## Instalar de vez

```powershell
.\instalar.ps1
```

Registra a tarefa `JarvisAgentePc` no Agendador, para iniciar no **logon deste usuário**, sem
janela de console (`javaw`) e reiniciando sozinha se cair.

Precisa ser no logon, e não um serviço do Windows: o agente depende da sessão interativa. O
`Robot` da captura não vê o desktop de outra sessão, o `LockWorkStation` age na sessão de quem
chama, e o Chrome em kiosk precisa de um desktop. Como serviço, três das dez ações não
funcionariam — e a captura responderia *"não consigo ver a tela"*.

A tarefa roda **sem elevação** (`-RunLevel Limited`): desligar, reiniciar e bloquear a própria
máquina não pedem administrador.

```powershell
Get-ScheduledTask -TaskName JarvisAgentePc            # estado
Stop-ScheduledTask -TaskName JarvisAgentePc           # parar
Unregister-ScheduledTask -TaskName JarvisAgentePc -Confirm:$false   # remover
```

## Log

`%LOCALAPPDATA%\jarvis-agente-pc\agente.log.0` — o `pcToken` nunca aparece nele.

O `.0` no fim não é erro de digitação: o `FileHandler` do Java numera as gerações, e são 3
arquivos de 1 MB rotativos. O `.0` é sempre o atual; `.1` e `.2` são os anteriores.

```powershell
Get-Content "$env:LOCALAPPDATA\jarvis-agente-pc\agente.log.0" -Tail 40 -Wait
```

## Os dois contratos de segurança

Estes dois são a razão de o agente existir em vez de uma ferramenta de "executar comando
qualquer", e valem mesmo que a VPS mude:

1. **Só abre app desta lista.** O Jarvis conhece apenas os *nomes* do `config.properties` — eles
   vão no cabeçalho `X-Pc-Apps` de cada consulta e são o que o modelo pode escolher. O caminho do
   executável nunca sai daqui, e nome fora da lista não abre nada. Tirar uma linha do
   `config.properties` tira o app do alcance do Jarvis na hora.
2. **Revalida tudo que a VPS validou.** O servidor também confere que a URL é https e que o app
   está na lista, mas é este processo que tem poder na máquina. Confiar na checagem do outro lado
   é o que transformaria um bug de lá num problema aqui.

Some a isso: toda execução passa por `ProcessBuilder` com **argumentos fixos**, nunca montando
linha de shell com pedaço vindo da VPS — e a VPS manda o que um modelo de linguagem escreveu. Onde
o PowerShell é inevitável (teclas de mídia e bateria), o `-Command` é uma constante escolhida por
`switch`.

E as ações irreversíveis não dependem só disto: `desligar` e `reiniciar` exigem um código de 4
dígitos confirmado pelo usuário, do lado do Jarvis, e aqui ainda esperam 60 segundos antes de
acontecer — tempo de desistir com *"cancela"*.

## As ações

| ação | o que faz aqui |
|---|---|
| `abrir_app` | `ProcessBuilder` com o caminho do `config.properties` |
| `abrir_url` | revalida https e chama `Desktop.browse` |
| `abrir_hud` | Chrome com `--kiosk --autoplay-policy=no-user-gesture-required` na `urlHud` |
| `bloquear_tela` | `rundll32.exe user32.dll,LockWorkStation` |
| `volume` | tecla de mídia por PowerShell/`SendKeys` (175 sobe, 174 abaixa, 173 alterna o mudo) |
| `captura_tela` | `Robot` em todos os monitores, PNG reduzido para 1600 px de largura |
| `status_pc` | CPU e memória pelo `OperatingSystemMXBean`, disco pelo `FileStore`, bateria por `Win32_Battery` |
| `desligar` / `reiniciar` | `shutdown /s /t 60` e `shutdown /r /t 60` |
| `cancelar_desligamento` | `shutdown /a` (código 1116, "nada agendado", volta como sucesso) |

## Quando algo não funciona

| sintoma | causa provável |
|---|---|
| log com `HTTP 401` | `pcToken` diferente do `PC_TOKEN` da VPS, ou `PC_TOKEN` vazio no `.env` |
| log com `HTTP 403` | a chamada saiu em http; o nginx só aceita `/api/pc/` por https |
| *"não consigo ver a tela"* | o agente não está na sessão do usuário (foi instalado como serviço?); confirme com `--testar` |
| `app '...' ignorado: caminho inválido` | no `.properties` a contrabarra é escape: use `C:\\Pasta\\x.exe` ou `C:/Pasta/x.exe` |
| *"não está na lista de apps deste PC"* | falta a linha `app.<nome>` ou o `.exe` mudou de lugar |
| o Jarvis diz que o PC está offline | o agente não está rodando: veja o log e `Get-ScheduledTask` |
