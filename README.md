# jarvis-agente-pc

O braço do [Jarvis](../jarvis) neste PC Windows. Ele pergunta à VPS se tem comando, executa e
devolve o resultado — abrir app, abrir página, abrir a HUD, bloquear a tela, mexer no volume,
tirar print, dizer como o PC está, desligar, reiniciar, cancelar o desligamento, criar/ler/editar
arquivos, rodar uma rotina cadastrada e executar as ações visuais do computer use.

E ele **acorda sozinho**: bata **duas palmas** ou diga "Jarvis" para abrir a tela do
Jarvis em tela cheia e ouvir a apresentação.

**Nada escuta porta nesta máquina.** Toda conexão é de dentro para fora (long polling em
`GET /api/pc/comandos`), como o próprio Jarvis faz com a API do Telegram. Sem porta aberta, sem
domínio, sem webhook.

Java 21, sem Spring, um jar só (shade). Dependências: Jackson 3 (o mesmo do Jarvis), commonmark
para DOCX/PDF, Apache POI para XLSX, PDFBox para ler PDF, JNA para integração com o Windows e o
whisper.cpp externo para o reconhecimento de voz offline.

## Criação de arquivos

O comando `criar_arquivo` gera MD, TXT, DOCX, PDF, XLSX ou CSV somente em `pastaArquivos`.
Sem configuração, usa a pasta Documentos do Windows, subpasta `Jarvis`. O nome enviado pelo
Jarvis não pode conter caminho; arquivos existentes recebem um sufixo numérico, sem sobrescrita.
O agente envia o arquivo à VPS em upload binário autenticado por `pcToken`, limitado a 8 MiB.
DOCX e PDF usam Markdown com tabelas; XLSX aceita até 5 abas, 2.000 linhas por aba, 30 colunas
e 20.000 células. CSV usa uma tabela e acrescenta apóstrofo a valores que poderiam virar fórmula.

## Localizar e ler arquivos

`listar_arquivos` importa os seis formatos suportados já presentes diretamente em `pastaArquivos`
e retorna IDs estáveis, filtros de nome/formato, paginação e estado. O catálogo local
`.jarvis-catalogo.properties` sobrevive a reinícios. Links, diretórios, arquivos acima de 8 MiB e
formatos inválidos não são importados. A criação também registra o arquivo no catálogo, sem mudar
o upload que entrega o anexo ao Telegram e à HUD.
A pasta `.lixeira` e os backups de edição em `%LOCALAPPDATA%` ficam fora desta listagem: o que foi
para a lixeira sai do catálogo e só aparece em `listar_lixeira`.

`ler_arquivo` usa o ID, nunca um caminho enviado pela VPS. Lê TXT e MD como UTF-8, DOCX com
parágrafos e tabelas, PDF por página, XLSX com endereço/tipo/fórmula/valor em cache e CSV com
aspas e quebras de linha. As respostas são parciais (até 12 mil caracteres e 12 segundos) e
indicam truncamento e continuação. Fórmulas, links e conteúdo do documento são apenas dados.
Linhas CSV e células XLSX grandes podem ser retomadas com `inicioLinha` ou `inicioCelula`; os
fragmentos devolvidos são partes do JSON do item e avançam até completar a linha ou célula.
PDF sem texto extraível requer OCR, ainda não implementado. Arquivo removido ou alterado desde o
cadastro é recusado até nova avaliação. Google Sheets nativo fica fora desta versão.

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
- `urlHud` — a tela do Jarvis. É a mesma que o `abrir_hud` e o despertar por voz abrem.
- `app.chrome` — obrigatório para a tela abrir.
- `app.<nome>=<caminho do .exe>` — um por programa liberado.

As chaves do despertar (`gatilho.palmas`, `gatilho.voz`, `microfone`, `palmas.sensibilidadeDb`,
`voz.probabilidadeMinima`, `hud.modo`, `energia.manterPcAcordado`) estão comentadas uma a uma no
`config.properties.example`. Todas têm padrão: um arquivo antigo, sem nenhuma delas, continua
valendo.

O `config.properties` tem o token e está no `.gitignore`. Mantenha assim.

## Hermes3D

Com as chaves `hermes3d.*` preenchidas, o agente liga o backend Hermes, o adaptador e o Studio,
e os religa se caírem. Antes da primeira vez, rode `npm run build` na pasta do Hermes3D e
`plugins/hermes3d-team/install.ps1` para instalar as ferramentas de equipe.
Serviço cuja porta já está aberta não é iniciado de novo.

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
chama, e o Chrome em kiosk precisa de um desktop. Como serviço, a captura, o bloqueio de tela, a
abertura da HUD e o computer use inteiro não funcionariam — e a captura responderia *"não consigo
ver a tela"*.

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
| `criar_arquivo` | gera o arquivo em `pastaArquivos` e faz o upload (ver [Criação de arquivos](#criação-de-arquivos)) |
| `listar_arquivos` / `ler_arquivo` | catálogo e leitura paginada (ver [Localizar e ler arquivos](#localizar-e-ler-arquivos)) |
| `preparar_edicao` / `aplicar_edicao` | proposta e gravação da edição (ver [Edição](#edição-de-txt-markdown-json-e-xlsx)) |
| `preparar_desfazer` / `aplicar_desfazer` | restauração do backup da edição |
| `mover_para_lixeira` | guarda o arquivo em `.lixeira/AAAA-MM-DD/` com registro ao lado (ver [Lixeira](#lixeira-reversível)) |
| `restaurar_da_lixeira` | devolve o arquivo ao lugar, com sufixo se o nome estiver ocupado |
| `listar_lixeira` | o que está na lixeira: nome, data, ID e quando expira |
| `rodar_rotina` | executa uma rotina do `rotinas.properties` (ver [Rotinas](#rotinas-manuais-no-windows)) |
| `computer_acao` | ação interna E2 de `tarefa_no_pc`, restrita ao monitor principal |
| `inferir` | chama o Ollama deste PC e devolve a resposta (ver [IA local](#ia-local-ollama)) |

### Computer use supervisionado

**Já validado no PC real:** hotkey, bandeja, captura e escala, mudança de resolução, interferência
por mouse/teclado, queda de rede, cancelamento remoto e uma tarefa completa até o resultado. Os
builds automatizados não cobrem entrada nativa do Windows nem disponibilidade dos modelos na conta,
então é essa conferência na máquina que vale como prova.

`computer.use.ativo=false` continua sendo o padrão do `config.properties`, para quem clona o
projeto não ganhar controle de mouse e teclado sem pedir. Ligar exige os dois lados: `true` aqui e
`COMPUTER_USE_ATIVO=true` na VPS. O agente continua sem porta aberta: busca cada ação pelo E2 e
devolve a captura necessária ao provedor.
Uma sessão por vez usa o monitor principal; coordenadas fora da captura, texto acima de 1000
caracteres, teclas não reconhecidas e esperas acima de dez segundos são recusados. Mudança na
geometria ou entrada local entre ações encerra a tarefa. O agente não executa shell nem scripts
vindos do modelo.

**Parada local:** Ctrl+Alt+Shift+F12 ou “Parar computer use” no ícone da bandeja. O bloqueio
permanece até selecionar “Permitir nova tarefa” localmente ou reiniciar o agente. Se o atalho
global do Windows não puder ser registrado, o executor recusa ações. A parada remota depende da
rede e não desfaz uma ação já iniciada. As capturas de resultado não entregues são descartadas
após cinco minutos; não há envio periódico ao Telegram.

## IA local (Ollama)

A VPS pode usar o Ollama deste PC como modelo: manda a ação `inferir`, o agente chama
`/api/chat` aqui mesmo e devolve a resposta no resultado. Continua sem porta aberta — o pedido
chega pelo long poll e a resposta volta pelo POST de resultado.

Vem desligado. Para ligar, com o Ollama instalado e o modelo baixado (`ollama pull qwen3.5:9b`):

| chave | padrão | |
|---|---|---|
| `ollama.ativo` | `false` | |
| `ollama.url` | `http://127.0.0.1:11434` | só `127.0.0.1` ou `localhost`, em http; outro endereço desliga a IA com aviso no log |
| `ollama.modelo` | `qwen3.5:9b` | |
| `ollama.contexto` | `16384` | vira o `options.num_ctx` |
| `ollama.keepAlive` | `20m` | quanto tempo o modelo fica na VRAM depois da última inferência |
| `ollama.timeoutSegundos` | `90` | a inferência inteira, incluindo carregar o modelo |
| `inferencia.maxKib` | `256` | teto do pedido que chega e do resultado que volta |

A URL é só local porque o agente manda para lá o que a VPS escreveu e devolve o que vier: outro
endereço transformaria o agente numa ponte da VPS para a rede de casa. O `localhost` é trocado por
`127.0.0.1`, sem passar pelo arquivo hosts.

**O corpo é montado aqui.** O `dados` do comando segue o formato do `/api/chat`, sem stream, mas
só três campos são aproveitados, e cada um é revalidado e reconstruído:

- `messages`: `role` entre `system`, `user`, `assistant` e `tool`; `content` em texto; `thinking`
  e `tool_calls` só no assistant; `tool_name` só no tool. `images` e campos desconhecidos são
  descartados.
- `tools`: `{"type":"function","function":{"name","description","parameters"}}`, com nome em
  `[A-Za-z0-9_-]{1,64}`.
- `think`, só se for booleano.

`model`, `options`, `keep_alive`, `stream` e `format` vindos da VPS são ignorados: o agente impõe o
modelo, o `num_ctx` e o `keep_alive` deste arquivo, com `stream=false`. A VPS não decide qual
modelo ocupa a GPU, nem por quanto tempo.

O resultado traz em `dados` a `message` (`content`, `thinking` e `tool_calls`, cada uma com
`function.name` e `function.arguments` em objeto), além de `done_reason`, `modelo`,
`prompt_eval_count`, `eval_count` e `total_duration`. O limite de `dados` é `inferencia.maxKib`
só para esta ação; as consultas de arquivo continuam com 32 KB.

**Uma inferência por vez.** A GPU é uma só: as inferências rodam numa thread própria, uma depois
da outra, com até 4 esperando. Enquanto isso o long poll segue e as outras ações (abrir app,
volume…) continuam valendo. Pedido que passa do prazo de início enquanto espera, ou que a VPS
cancela, nem chega ao Ollama.

**Falhas** voltam com `dados.codigoErro`, que é o que a VPS usa para decidir o fallback:

| código | quando |
|---|---|
| `ollama_offline` | conexão recusada: o Ollama não está rodando |
| `modelo_ausente` | o Ollama respondeu 404 / "not found": falta o `ollama pull` |
| `sem_memoria` | o Ollama disse que falta memória para o modelo |
| `timeout` | passou de `ollama.timeoutSegundos`, ou do prazo de início enquanto esperava na fila |
| `pedido_grande` | o pedido passou de `inferencia.maxKib` (nada é enviado ao Ollama) |
| `resposta_grande` | a resposta passou de `inferencia.maxKib` |
| `pedido_invalido` | estrutura fora do formato acima |
| `fila_cheia` | já havia 4 inferências esperando |
| `ia_desativada` | `ollama.ativo=false` (ou URL recusada) |
| `falha_ollama` | qualquer outra falha |

### O cabeçalho `X-Pc-Ia`

Vai em toda consulta de comandos, ao lado do `X-Pc-Apps`:

```
ativo=1;ollama=1;modelo=qwen3.5:9b;carregado=1;gpu=1;vramLivreMb=9216;vramTotalMb=12288;gpuUso=35;ocupada=0;inferindo=0
```

- `ollama` / `carregado`: o `GET /api/ps` respondeu / o modelo configurado está na memória.
- `gpu`, `vramLivreMb`, `vramTotalMb` e `gpuUso` vêm do `nvidia-smi --query-gpu=memory.used,memory.total,utilization.gpu --format=csv,noheader,nounits`
  (argumentos fixos, limite de 3 s), lido da primeira GPU. Sem nvidia-smi: `gpu=0`, sem esses campos.
- `ocupada=1`: uso de GPU ≥ 70% sem inferência nossa em andamento nem terminada há menos de 5 s —
  um jogo aberto, por exemplo.
- `inferindo`: há inferência rodando agora.
- Com a IA desligada, o cabeçalho é só `ativo=0`.

A medida vale 15 s; só o `inferindo` é lido a cada consulta. Cada valor passa por um filtro de
caracteres (`[A-Za-z0-9._:/+-]`, até 64), e o cabeçalho inteiro fica em até 512.

### O cabeçalho `X-Pc-Foco`

Também em toda consulta de comandos: o **nome do processo** em primeiro plano — `chrome`, `Code`,
`WINWORD`. **Nunca o título da janela**, que pode ter nome de arquivo, assunto de e-mail ou texto
de página; a interface do user32 do agente nem declara a função que lê o título.

O nome sai do caminho do executável pela API do Windows (`GetForegroundWindow`,
`GetWindowThreadProcessId`, `QueryFullProcessImageNameW`), só leitura e sem abrir processo nem
PowerShell. Vale 30 s. Só passa nome em `[A-Za-z0-9._-]` com até 40 caracteres, sem o `.exe`; o que
não cabe é recusado inteiro, não filtrado. Qualquer falha — sem janela em foco, processo que não
deixa ler, nome fora do padrão — é cabeçalho ausente. `reportarFoco=false` desliga.

## O despertar

Bata **duas palmas** ou diga *"Jarvis"* — a palavra **sozinha**, sem frase em volta — para abrir a
tela em tela cheia e ouvir a apresentação. Os dois gatilhos dividem o mesmo microfone e valem ao mesmo tempo; qualquer um dos
dois abre a tela. O agente também continua aceitando os comandos existentes para abrir a HUD,
colocá-la em repouso e verificar se a janela segue aberta.

**Tudo offline.** O reconhecimento acontece aqui, nesta máquina. Nenhum áudio sai daqui antes do
gatilho — e depois dele, nenhum áudio sai *pelo agente*: quem passa a escutar é a própria HUD, no
navegador, como já era antes.

### As duas palmas

O padrão aceito é **duas palmas com 200 a 700 ms entre elas** — bem rápido, tipo "pá-pá". Três ou
mais seguidas não disparam nada: a decisão só sai 400 ms depois da segunda palma, se nada mais
vier, e é isso que separa "chamei o Jarvis" de "a sala aplaudiu". Depois de abrir a tela, o
detector fica surdo por 5 segundos.

Não é só volume. Uma palma é um estouro **curto e agudo**, e são três filtros juntos que a separam
do resto: altura acima do ruído de fundo (que é móvel, medido continuamente — a mesma sala tem
piso diferente com o ar ligado e de madrugada), forma do pico (sobe ao máximo em até 5 ms e acaba
em até 80 ms) e timbre (muito agudo). A batida na mesa e a porta batendo são graves e sobem mais
devagar; a nota de música demora a cair.

O "acabou" tem uma sutileza que custou um bug: ele é o que vier primeiro entre *chegar ao piso da
sala* e *cair 20 dB do próprio máximo*. Só a primeira regra existia, e ela embute um piso
realista — perto de -60 dBFS, como nas cenas de `src/test/resources`. Microfone com supressão de
ruído entrega **silêncio digital** entre os sons (piso de -97 dBFS nesta máquina, nos dois
microfones), e aí "voltar ao silêncio" passava a exigir uma queda de 90 dB, que não cabe em 80 ms.
O efeito era invertido e confuso: palma forte era recusada, palma fraca passava — porque a fraca
tinha menos a cair. Medir a queda a partir do próprio máximo tira a dependência do piso.

### Qual microfone

Há duas entradas de captura nesta máquina: `USB Microphone` (o mic de mesa) e
`High Definition Audio Device` (a entrada onboard, do fone). Com `microfone=` vazio o agente usa o
**padrão do Windows**, que troca sem aviso — e o gatilho quebra sem motivo aparente, porque os dois
microfones têm sensibilidade bem diferente. Por isso o `config.properties` fixa o de mesa pelo
nome. A busca é por pedaço do nome, sem diferenciar maiúsculas.

### A sensibilidade

O único número que depende da sua sala é `palmas.sensibilidadeDb`, e ele se mede:

```powershell
Stop-ScheduledTask -TaskName JarvisAgentePc
java -jar target\jarvis-agente-pc.jar config.properties --calibrar
```

Pare o agente antes: só uma cópia roda por vez (há um *lock* em
`%LOCALAPPDATA%\jarvis-agente-pc\agente.lock`). A calibração pede 5 segundos de silêncio e depois
5 pares de palmas, na distância de uso; roda 13 limiares em paralelo sobre o mesmo áudio e sugere
o menor que reconheceu todos os pares, com margem. Ela **não** escreve no `config.properties` —
valide contra conversa e ruído antes de salvar o valor na mão. O áudio fica só na memória.

Se a tela abrir sozinha, suba o valor de 2 em 2; se as palmas não forem reconhecidas, desça. Há um
teto prático: cada disparo loga quantos dB a palma passou do ruído, e subir o limiar acima desse
número passa a exigir palma forte. Neste PC, com o mic de mesa, uma palma leve mede ~15 dB acima do
ruído e uma palma firme passa de 70 — o limiar de 12 aceita as duas.

### Os dois estados

| | microfone | energia |
|---|---|---|
| **repouso** (aguardando palmas, voz ou comando remoto) | aberto, no agente | PC acordado, monitor pode apagar |
| **ativo** (tela aberta) | **solto** — quem escuta é a HUD | PC e monitor acordados |

Soltar o microfone em ativo não é economia de CPU: com o agente e a HUD escutando ao mesmo tempo,
a voz do Jarvis saindo nas caixas voltaria pelo microfone e o detector ouviria a si mesmo.

Volta ao repouso quando a janela fecha, ou com *"Jarvis, modo repouso"*. Em repouso, o agente
aguarda as palmas e o comando de voz.

Se a tela já estiver aberta, outro pedido para abri-la apenas traz a janela existente para a frente.

### Primeira vez: configurar o perfil da tela

A tela abre num **perfil do Chrome só dela** (`%LOCALAPPDATA%\JarvisHud`). Isso mantém o Jarvis
longe das suas abas e — o detalhe que faz tudo funcionar — faz o processo do Chrome durar enquanto
a janela existir, que é como o agente sabe que a tela fechou. Com o perfil normal, o Chrome já
aberto receberia a URL e o processo novo morreria no mesmo instante: o agente acharia que a tela
fechou um segundo depois de abri-la.

Em troca, esse perfil nasce vazio, e a HUD guarda a URL da API e o token no `localStorage`, que é
por perfil. **Uma vez, na instalação:**

1. Use o comando de voz ou mande *"abre a HUD"* pelo Telegram para o perfil nascer.
2. A HUD vai cair na tela de configuração. Preencha a URL da API e o `HUD_TOKEN`.
3. Pronto. O perfil fica em disco e os despertares seguintes caem direto na tela.

### "Jarvis": baixar o modelo

```powershell
.\baixar-modelo.ps1
```

O script baixa whisper.cpp e o modelo base multilingue (cerca de 145 MB) para `ferramentas\` e
`modelos\`, ambos no `.gitignore`. Os caminhos são resolvidos a partir do `config.properties`,
mesmo quando a tarefa agendada inicia o agente com outro diretório atual.

**Sem os arquivos nada quebra**: o agente avisa que a voz offline está indisponível; os comandos
remotos para abrir a HUD continuam valendo. A voz só aceita a palavra isolada `"Jarvis"`;
a confiança configurada é verificada nos tokens dela, enquanto uma única grafia
alternativa observada para o nome ("Jardes") é normalizada para lidar com a limitação do modelo.

### Energia

Com a tela aberta o agente pede ao Windows que não apague o monitor nem suspenda a máquina — um
HUD que apaga no meio de uma frase não serve para nada. Em repouso, com
`energia.manterPcAcordado=true` (o padrão), só impede a suspensão: o monitor apaga normalmente,
mas o PC continua aguardando a palavra de ativação.

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
| *"Jarvis"* não funciona | falta o modelo: rode `.\baixar-modelo.ps1` e veja o log na partida |
| a tela abre na configuração | é a primeira vez nesse perfil do Chrome; preencha URL e `HUD_TOKEN` uma vez |
| a tela abre muda | o Chrome só libera autoplay com a flag que o agente passa; abrindo a HUD à mão, clique uma vez |
| `não consegui abrir o microfone` | outro programa o tomou, ou falta permissão em Privacidade > Microfone |

## Rotinas manuais no Windows

Copie `rotinas.properties.example` para `rotinas.properties` e ajuste pasta e programa para
caminhos absolutos reais do seu PC. O exemplo Maven aponta para um projeto de demonstração; o
exemplo Claude é apenas de análise. Nenhum deles roda automaticamente. O agente lê o cadastro na
partida, anuncia somente nome, descrição e capacidades e recusa nomes desconhecidos localmente.
Reinicie o agente depois de alterar o cadastro.

Cada rotina define `tipo`, `pasta`, `programa`, `argumentos.count`, cada `argumentos.N`,
`timeoutSegundos`, `capacidades` e `confirmacao`. Para rotinas com código, publicação, remoção ou
agentes, use `confirmacao=sempre`. Caminhos `.cmd` não são programas genéricos: somente o
adaptador `maven` aceita `mvn.cmd`, com os três argumentos fixos `-q`, `-B`, `test` e caminho sem
metacaracteres do `cmd.exe`. Para os demais tipos, aponte para `.exe` nativo.
O Jarvis pede confirmação para toda rotina; o agente aplica novamente sua política local.

`claude_leitura` requer Claude Code 2.1.248 ou superior, com `claude.exe` compatível com
`--restricted`; roda com ferramentas de leitura,
`dontAsk`, sem MCP, sem comandos slash e recebe o pedido por stdin. Confira também as permissões
efetivas da conta Windows e do projeto, pois stdin não restringe o agente por si. O diário E2 fica
em `%LOCALAPPDATA%/jarvis-agente-pc/operacoes-e2`; mantenha essa pasta entre reinícios. Um início
registrado sem resultado conhecido vira `resultado_incerto` e nunca é repetido automaticamente.
O cancelamento em execução é observado por consulta à VPS e pode demorar alguns segundos; somente
processos descendentes da execução identificada são encerrados.

O E2 transporta arquivos gerados por upload autenticado (até 8 MiB), capturas PNG/JPEG (até 8 MiB
decodificados), respostas estruturadas das consultas de arquivos (até 32 KiB) e respostas de
`inferir` (até `inferencia.maxKib`). O diário local
guarda esses resultados até a VPS confirmar o recebimento; depois remove os payloads da cópia local.
Conserve o diário entre reinícios para deduplicar comandos, inclusive quando a resposta HTTP se perde.
## Lixeira reversível

Apagar arquivo da `pastaArquivos` é reversível. `mover_para_lixeira` recebe **só o ID catalogado**,
nunca um caminho, confere que o arquivo está mesmo dentro da pasta autorizada e o guarda em
`<pastaArquivos>/.lixeira/AAAA-MM-DD/` como dois arquivos:

```
3f2a...c91.json                 o registro: id, nome original, formato, hash e data
3f2a...c91-relatorio.txt.dados  os bytes originais
```

O registro leva o nome do UUID, e não o do arquivo, porque `json` é formato catalogável:
`relatorio.txt` e `relatorio.txt.json` na mesma pasta fariam o registro de um ser o conteúdo do
outro. Com o nome em forma de UUID, arquivo de usuário não pode ser confundido com registro — por
forma, não por convenção. A extensão `.dados` fica fora dos formatos suportados, então o conteúdo
retido nunca volta ao catálogo por acidente. O registro é **dado**, nunca um caminho a resolver: o
nome guardado por nós passa pelas mesmas regras de nome do catálogo na hora de restaurar, e o
`.dados` do item é achado pelo prefixo do ID.

O movimento grava o registro **antes** de mexer no arquivo. Na ordem contrária, uma queda no meio
deixaria um arquivo órfão invisível nas duas listagens — perda silenciosa, exatamente o que uma
lixeira existe para evitar. Na partida, o agente reconcilia o que ficou pela metade: movimento sem
arquivo tem o registro descartado (o original continua no lugar), arquivo já movido tem o registro
promovido, e restauração interrompida volta atrás. O move é sempre atômico; disco que não oferece
isso recusa a ação com o arquivo intacto, porque um copiar-e-apagar pela metade é a perda que a
lixeira deveria impedir. Arquivo aberto em outro programa — o Excel numa planilha, por exemplo —
também recusa, pedindo para fechar. Edição em voo no mesmo arquivo bloqueia o movimento: são os
estados que a recuperação pós-queda vai tentar concluir.

`restaurar_da_lixeira` recebe o ID do registro, confere o hash dos bytes guardados e devolve o
arquivo ao nome original. Se já existir arquivo com aquele nome, restaura como `nome-1.ext` e avisa
qual nome usou — nada é sobrescrito. **A restauração devolve um ID novo**, porque a entrada antiga
saiu do catálogo no movimento; propostas de edição e leituras registradas com o ID antigo morrem
junto.

`lixeira.diasRetencao` (padrão 30) diz quantos dias o item fica. Passado o prazo, ele é apagado de
vez: o expurgo roda na partida e uma vez por dia, decidindo pelo nome da pasta datada. Pasta com
nome que não é data, ou com data no futuro, fica onde está, com aviso no log — nesta pasta o
usuário também mexe, e apagar o desconhecido seria o jeito de um bug daqui virar um apagão lá.

## Edição de TXT, Markdown, JSON e XLSX

`preparar_edicao` recebe o ID de `listar_arquivos`/`ler_arquivo`, o SHA-256
`versaoEsperada`, destino `original` ou `copia` e até dez operações. O agente só
aceita arquivos diretamente em `pastaArquivos`, após leitura recente da mesma versão.
`substituir_trecho` exige uma ocorrência; `substituir_todas_ocorrencias` troca todas
as ocorrências literais, com `ignorarMaiusculas` opcional, e informa a contagem.
Leituras paginadas da mesma versão comprovam leitura integral quando cobrem todo o
conteúdo; esse registro expira dez minutos após a última página lida.
Ele preserva UTF-8 com ou sem BOM e quebras LF/CRLF. A proposta é imutável e expira
em dois minutos. `aplicar_edicao` recebe somente o `editId`; o servidor exige nova
mensagem autenticada antes de sobrescrever o original. Para cópia, o nome recebe
`-copia-<prefixo do editId>`.

Antes de substituir, o agente valida o hash e o arquivo novamente, guarda backup em
`%LOCALAPPDATA%/jarvis-agente-pc/edicoes` e exige move atômico. `preparar_desfazer`
e `aplicar_desfazer` só restauram backup íntegro se o arquivo ainda tiver o hash
produzido pela edição. A retenção padrão é de dez versões por arquivo e 100 MiB no
total; após expurgo não é possível desfazer a versão removida. Os limites são
configurados em `config.properties.example`.

JSON aceita somente `atualizar_valor` de escalar existente por JSON Pointer, com tipo
e valor anterior esperados. Chaves duplicadas, caminho ausente e tipo divergente são
recusados. Os demais campos são preservados semanticamente; a serialização pode mudar
espaços e representação lexical. Não há inserção ou remoção JSON nesta fase.

XLSX usa Apache POI para atualizar ou preencher células, acrescentar/inserir linhas,
inserir colunas, criar abas e expandir filtro. O leitor permite buscar valor exato, restringindo por aba,
cabeçalho ou tabela, e devolve todas as correspondências até 50. Busca ambígua não
autoriza escolher a primeira ocorrência. `atualizar_por_item` recebe item e cabeçalho,
resolve a célula no agente e recusa correspondências repetidas. A proposta confere
aba/célula, tipo e valor anterior.
`listar_arquivos` também encontra `tabela_exemplo.xlsx` pela busca “tabela exemplo”.
Na preparação, o catálogo informa se esse nome falado identifica um único XLSX.
Na leitura XLSX, `tipo` e `valorAnterior` correspondem aos parâmetros da edição:
`booleano` usa um booleano JSON, e fórmula usa a expressão em vez do valor em cache.
Estilos e abas não tocadas são mantidos pela gravação do livro; texto que
começa por `=`, `+`, `-` ou `@` é gravado como texto. Fórmulas novas passam pela lista
restrita do criador e o resultado é apenas marcado para recálculo ao abrir, sem avaliação
nem atualização confiável do cache. Substituir fórmula por outro tipo requer autorização
explícita. Mesclas e proteção impedem edição do alvo.

O leitor retorna `ultimaLinhaFisica`, `proximaLinhaFisica`, `intervaloFiltro` (ou null)
e `tabelas` com nome/intervalo, até 50, sinalizando `tabelasTruncadas` quando necessário.
Os quatro limites explícitos de linha/coluna incluem células ausentes com `existe=false`,
`tipo=vazio` e `valorAnterior=null`; uma célula BLANK existente tem `existe=true`.

Contrato das operações (além de `tipo`):

| Operação | Campos obrigatórios |
| --- | --- |
| `atualizar_celula`, `preencher_celula_vazia` | `aba`, `celula`, `tipoAnterior`, `valorAnterior`, `tipoNovo`, `valorNovo` |
| `atualizar_por_item` | `itemValor`, `cabecalhoAlvo`, `tipoAnterior`, `valorAnterior`, `tipoNovo`, `valorNovo` |
| `acrescentar_linha` | `aba`, `linhaEsperada` = próxima linha física |
| `inserir_linha`, `inserir_coluna` | `aba` e `linha` ou `coluna`, começando em 1 |
| `criar_aba` | `aba` |
| `expandir_filtro` | `aba`, `intervaloAnterior`, `intervaloNovo`, sem nome da aba nas faixas |

`atualizar_celula` exige célula existente; `preencher_celula_vazia` pode criar uma
célula ausente com tipo anterior `vazio` e valor anterior JSON `null`.
`acrescentar_linha` cria somente uma linha vazia no fim físico, incluindo notas e linhas
formatadas no cálculo. Preencher essa linha requer operações adicionais.

Para incluir dados em A4:H4 quando o filtro é A1:H3 e há notas da linha 5 em diante,
proponha preenchimentos de A4:H4 e `expandir_filtro` de A1:H3 para A1:H4 juntos.
A expansão mantém cabeçalho/colunas, estende somente o fim e atualiza o nome definido
do filtro. Cada linha nova deve estar originalmente vazia e receber dados na proposta.
Recusa sobreposição com notas/dados, mesclas, tabelas estruturadas na aba, critérios ou
ordenação ativos, e mudanças estruturais na mesma aba. Não calcula critérios de filtro.

Limitação observada na conferência visual: o XLSX grava o intervalo A1:H4 corretamente,
mas o LibreOffice pode expandi-lo em memória ao abrir o menu quando há uma nota contígua
na linha 5. A prévia avisa dessa situação; as notas permanecem nas posições originais.
Não há inserção implícita de espaçador. Cabeçalho, larguras e três abas do arquivo de
teste abriram sem reparo. Consulte a
[ajuda do LibreOffice sobre expansão automática](https://help.libreoffice.org/latest/en-GB/text/scalc/guide/autofilter.html).

Recusas conhecidas são `FalhaEdicao`, com código estável e mensagem controlada.
`dados.codigoErro` distingue `CELULA_INEXISTENTE`, `POSICAO_DIVERGENTE`, conflitos,
`RECURSO_INCOMPATIVEL`, `ACESSO_NEGADO`, `ARQUIVO_BLOQUEADO`, `ARQUIVO_INDISPONIVEL`,
`FALHA_DE_IO` e `FALHA_INTERNA`. `RESULTADO_INCERTO` mantém o fluxo de reconciliação.
O log e o resumo persistido no diário após envio incluem ID do comando, etapa,
operação/índice (1 em diante; 0 quando não identificada), código e classes da exceção
e causa. Não incluem conteúdo das células, credenciais ou mensagens de bibliotecas.
Não repetir automaticamente parâmetros recusados ou uma gravação de resultado incerto.

Regressões: `CorrecaoEdicaoXlsxTest`, `DiagnosticoEdicaoTest` e os testes anteriores
de edição/leitura. A regressão de gravação e desfazer gera
`target/validacao-edicao/planilha-editada.xlsx` para conferir aparência no LibreOffice.

Ao acrescentar linha, o agente expande tabelas que terminam na linha anterior e séries
de gráfico com intervalo direto da mesma aba. Inserção estrutural em tabela ou com
gráfico é recusada, salvo cópia simplificada explicitamente confirmada, que omite os
gráficos indicados na prévia. Arquivos criptografados, assinados, com macros, vínculos
externos ou recursos não preserváveis são recusados. O XLSX editável respeita o limite
de bytes configurado e limites internos de 256 entradas ZIP, 32 MiB expandidos,
16 MiB por entrada e 100 mil células, sem reduzir as proteções do POI.

O agente tenta obter bloqueio exclusivo antes de gravar. Editores externos que não
respeitam locks ainda podem alterar o arquivo entre a última checagem e o move;
o filesystem não oferece compare-and-swap perfeito nesse intervalo. Após queda na
fronteira da gravação, o agente usa o diário e hashes para comprovar o resultado;
quando não consegue, informa `resultado_incerto`, sem aplicar de novo.
