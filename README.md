# jarvis-agente-pc

O braço do [Jarvis](../jarvis) neste PC Windows. Ele pergunta à VPS se tem comando, executa e
devolve o resultado — abrir app, abrir página, abrir a HUD, bloquear a tela, mexer no volume,
tirar print, dizer como o PC está, desligar, reiniciar e cancelar o desligamento.

E ele **acorda sozinho**: duas palmas, ou "Jarvis, iniciar", abrem a tela do Jarvis em tela cheia
e ele se apresenta falando. Ver [O despertar](#o-despertar).

**Nada escuta porta nesta máquina.** Toda conexão é de dentro para fora (long polling em
`GET /api/pc/comandos`), como o próprio Jarvis faz com a API do Telegram. Sem porta aberta, sem
domínio, sem webhook.

Java 21, sem Spring, um jar só. Dependências: Jackson 3 (o mesmo do Jarvis), o Vosk para o
reconhecimento de voz offline e a JNA que ele traz.

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
- `urlHud` — a tela do Jarvis. É a mesma que o `abrir_hud` abre e a que o despertar acende; não
  existe uma segunda chave para isso.
- `app.chrome` — obrigatório para a tela abrir.
- `app.<nome>=<caminho do .exe>` — um por programa liberado.

As chaves do despertar (`gatilho.palmas`, `gatilho.voz`, `microfone`, `palmas.sensibilidadeDb`,
`voz.confiancaMinima`, `hud.modo`, `energia.manterPcAcordado`) estão comentadas uma a uma no
`config.properties.example`. Todas têm padrão: um arquivo antigo, sem nenhuma delas, continua
valendo.

O `config.properties` tem o token e está no `.gitignore`. Mantenha assim.

## Rodar na mão (para testar)

```powershell
java -jar target\jarvis-agente-pc.jar config.properties
```

A partida mostra a URL e os apps liberados. Daí, pelo Telegram: *"status do PC"*.

### Calibrar o gatilho por palmas

```powershell
java -jar target\jarvis-agente-pc.jar config.properties --calibrar
```

Mede 5 segundos de silêncio, pede 5 pares de palmas e sugere o `palmas.sensibilidadeDb`. Não fala
com a VPS. **Faça isto antes de confiar no despertar**: o número certo depende da sua sala, da
distância até o microfone e do ganho dele — um valor bom no escritório é surdo na sala grande.

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
| `abrir_hud` | Chrome em perfil próprio na `urlHud` (ver [O despertar](#o-despertar)) |
| `bloquear_tela` | `rundll32.exe user32.dll,LockWorkStation` |
| `volume` | tecla de mídia por PowerShell/`SendKeys` (175 sobe, 174 abaixa, 173 alterna o mudo) |
| `captura_tela` | `Robot` em todos os monitores, PNG reduzido para 1600 px de largura |
| `status_pc` | CPU e memória pelo `OperatingSystemMXBean`, disco pelo `FileStore`, bateria por `Win32_Battery` |
| `desligar` / `reiniciar` | `shutdown /s /t 60` e `shutdown /r /t 60` |
| `cancelar_desligamento` | `shutdown /a` (código 1116, "nada agendado", volta como sucesso) |
| `modo_repouso` | fecha a tela; o agente volta a escutar o gatilho |
| `falso_positivo` | conta um alarme falso e devolve o total |

## O despertar

Duas palmas, ou *"Jarvis, iniciar"*, abrem a tela em tela cheia e o Jarvis se apresenta falando.

**Tudo offline.** O reconhecimento acontece aqui, nesta máquina. Nenhum áudio sai daqui antes do
gatilho — e depois dele, nenhum áudio sai *pelo agente*: quem passa a escutar é a própria HUD, no
navegador, como já era antes.

### Os dois estados

| | microfone | energia |
|---|---|---|
| **repouso** (ouvindo o gatilho) | aberto, no agente | PC acordado, monitor pode apagar |
| **ativo** (tela aberta) | **solto** — quem escuta é a HUD | PC e monitor acordados |

Soltar o microfone em ativo não é economia de CPU: com o agente e a HUD escutando ao mesmo tempo,
a voz do Jarvis saindo nas caixas voltaria pelo microfone e o detector ouviria a si mesmo.

Volta ao repouso quando a janela fecha, ou com *"Jarvis, modo repouso"*.

Bater palmas com a tela **já aberta** não abre outra janela: traz esta para a frente e o Jarvis
diz só *"Já estou aqui, senhor."*. Esse pedido é a única vez em que o agente fala com a VPS sem
ter recebido um comando antes — e mesmo nela ele manda só uma *chave* de frase, nunca o texto.

### Primeira vez: configurar o perfil da tela

A tela abre num **perfil do Chrome só dela** (`%LOCALAPPDATA%\JarvisHud`). Isso mantém o Jarvis
longe das suas abas e — o detalhe que faz tudo funcionar — faz o processo do Chrome durar enquanto
a janela existir, que é como o agente sabe que a tela fechou. Com o perfil normal, o Chrome já
aberto receberia a URL e o processo novo morreria no mesmo instante: o agente acharia que a tela
fechou um segundo depois de abri-la.

Em troca, esse perfil nasce vazio, e a HUD guarda a URL da API e o token no `localStorage`, que é
por perfil. **Uma vez, na instalação:**

1. Bata palmas (ou mande *"abre a HUD"* pelo Telegram) para o perfil nascer.
2. A HUD vai cair na tela de configuração. Preencha a URL da API e o `HUD_TOKEN`.
3. Pronto. O perfil fica em disco e os despertares seguintes caem direto na tela.

### "Jarvis, iniciar": baixar o modelo

```powershell
.\baixar-modelo.ps1
```

São ~45 MB do modelo pequeno de português do Vosk, para `modelos\` (que está no `.gitignore`). O
agente procura ali qualquer pasta com `am\final.mdl` ou `final.mdl` na raiz (como no modelo de
português 0.3), então trocar de versão do modelo não pede mexer na configuração.

**Sem o modelo nada quebra**: o log diz `gatilho de voz desligado: não achei o modelo do Vosk` e
as palmas continuam valendo.

A gramática é fechada em `"jarvis iniciar"`, `"jarvis inicia"` e `[unk]`. Não é economia, é
precisão: com vocabulário livre o modelo pequeno acha "jarvis" em meia conversa. E a confiança
cobrada é a **menor** entre as palavras, não a média — "jarvis" certíssimo com "iniciar" no chute
daria média boa, e é justamente esse o caso que não pode acordar a casa.

### Como uma palma é reconhecida

Três filtros, e o som precisa passar nos três:

- **altura** — pelo menos `palmas.sensibilidadeDb` acima do ruído de fundo, que é medido
  continuamente (a mesma sala tem piso diferente de dia e de madrugada);
- **forma** — sobe ao máximo em até 5 ms e volta ao silêncio em até 80 ms. A batida na mesa sobe
  mais devagar; a nota de música demora a cair;
- **timbre** — muito agudo (taxa de cruzamento por zero alta). A batida na mesa e o passo no chão
  são graves.

E o gatilho é **exatamente duas**, com 200 a 700 ms entre elas, seguidas de 400 ms sem uma
terceira — aplauso não é chamado. Depois de disparar, o detector fica surdo por 5 segundos.

Errou? *"Jarvis, foi engano"* conta o alarme falso (o total vai para o log). Se virar rotina, rode
`--calibrar` e suba a sensibilidade.

Os testes do detector usam WAVs **sintetizados** (`src/test/resources`), gerados pelo
`GeradorDeWavDeTeste`: duas palmas disparam; uma, três, batida na mesa e fala normal não. Eles
provam a lógica, não a sua sala. Para trocá-los por gravações reais, grave em 16 kHz mono 16 bits
e mantenha os mesmos nomes de arquivo.

### Energia

Com a tela aberta o agente pede ao Windows que não apague o monitor nem suspenda a máquina — um
HUD que apaga no meio de uma frase não serve para nada. Em repouso, com
`energia.manterPcAcordado=true` (o padrão), só impede a suspensão: o monitor apaga normalmente,
mas o PC continua ouvindo as palmas.

Para conferir:

```powershell
powercfg /requests
```

Com a tela aberta devem aparecer pedidos em **DISPLAY** e **SYSTEM**; em repouso, só em
**SYSTEM**. Ao encerrar o agente, os dois somem — é para isso que existe o *shutdown hook*, já que
o pedido de energia vale por thread e some junto com ela.

## Quando algo não funciona

| sintoma | causa provável |
|---|---|
| log com `HTTP 401` | `pcToken` diferente do `PC_TOKEN` da VPS, ou `PC_TOKEN` vazio no `.env` |
| log com `HTTP 403` | a chamada saiu em http; o nginx só aceita `/api/pc/` por https |
| *"não consigo ver a tela"* | o agente não está na sessão do usuário (foi instalado como serviço?); confirme com `--testar` |
| `app '...' ignorado: caminho inválido` | no `.properties` a contrabarra é escape: use `C:\\Pasta\\x.exe` ou `C:/Pasta/x.exe` |
| *"não está na lista de apps deste PC"* | falta a linha `app.<nome>` ou o `.exe` mudou de lugar |
| o Jarvis diz que o PC está offline | o agente não está rodando: veja o log e `Get-ScheduledTask` |
| palmas não acordam nada | rode `--calibrar`; confira `gatilho.palmas=true`, `urlHud` e `app.chrome` |
| acorda sozinho o tempo todo | `palmas.sensibilidadeDb` baixo demais: rode `--calibrar` e suba |
| *"Jarvis, iniciar"* não funciona | falta o modelo: rode `.\baixar-modelo.ps1` e veja o log na partida |
| a tela abre na configuração | é a primeira vez nesse perfil do Chrome; preencha URL e `HUD_TOKEN` uma vez |
| a tela abre muda | o Chrome só libera autoplay com a flag que o agente passa; abrindo a HUD à mão, clique uma vez |
| `não consegui abrir o microfone` | outro programa o tomou, ou falta permissão em Privacidade > Microfone |
