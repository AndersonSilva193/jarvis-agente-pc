package dev.jarvis.agente;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import dev.jarvis.agente.gatilho.Calibrador;
import dev.jarvis.agente.gatilho.Despertador;
import dev.jarvis.agente.gatilho.EnergiaDoWindows;
import dev.jarvis.agente.gatilho.JanelaDaHud;

/**
 * O agente do Jarvis no PC Windows.
 *
 * Fica perguntando à VPS se tem comando (long polling), executa e posta o resultado. <b>Nada
 * escuta porta nesta máquina</b> — toda conexão é de dentro para fora, igualzinho ao
 * {@code TelegramPoller} do Jarvis do outro lado.
 *
 * Roda na sessão interativa do usuário, por tarefa agendada no logon (ver {@code instalar.ps1}).
 * Como serviço não funcionaria: o {@code Robot} não veria a tela, o {@code LockWorkStation} agiria
 * na sessão errada e o Chrome em kiosk não teria desktop para abrir.
 */
public final class AgentePc {

    private static final java.util.Set<String> ACOES_E2 = java.util.Set.of(
            "abrir_app", "abrir_url", "abrir_hud", "bloquear_tela", "volume", "captura_tela",
            "status_pc", "desligar", "reiniciar", "cancelar_desligamento", "modo_repouso",
            "falso_positivo", "criar_arquivo", "listar_arquivos", "ler_arquivo", "rodar_rotina",
            "computer_acao", "preparar_edicao", "aplicar_edicao", "preparar_desfazer",
            "aplicar_desfazer");

    private static final Logger log = Logger.getLogger(AgentePc.class.getName());

    private static final Duration ESPERA_INICIAL = Duration.ofSeconds(1);
    private static final Duration ESPERA_MAXIMA = Duration.ofSeconds(60);

    private AgentePc() {
    }

    public static void main(String[] args) throws Exception {
        try (InstanciaUnica instancia = InstanciaUnica.tentar(pastaDeDados())) {
            if (instancia == null) {
                System.err.println("O agente já está em execução. Pare-o antes de calibrar ou iniciar outra cópia.");
                return;
            }
            executar(args);
        }
    }

    private static void executar(String[] args) throws Exception {
        String ultimo = args.length > 0 ? args[args.length - 1] : "";
        boolean autoteste = "--testar".equals(ultimo);
        boolean calibrar = "--calibrar".equals(ultimo);
        Path arquivo = args.length > 0 && !args[0].startsWith("--")
                ? Path.of(args[0])
                : Configuracao.padrao();
        configurarLog();

        Configuracao config;
        try {
            config = Configuracao.ler(arquivo);
        } catch (IOException e) {
            // Erro de configuração não melhora com tentativa: é para o usuário ler e corrigir.
            log.severe("configuração inválida: " + e.getMessage());
            System.exit(1);
            return;
        }
        log.info("agente de pé, falando com " + config.urlApi() + "; apps liberados: "
                + (config.apps().isEmpty() ? "nenhum" : config.cabecalhoDeApps()));

        if (autoteste) {
            autotestar(config);
            return;
        }
        if (calibrar) {
            // Não fala com a VPS: é só esta máquina, este microfone e esta sala.
            new Calibrador(config).rodar();
            return;
        }

        ClienteJarvis cliente = new ClienteJarvis(config);
        CatalogoRotinas catalogo = CatalogoRotinas.ler(arquivo.toAbsolutePath().getParent()
                .resolve("rotinas.properties"));
        DiarioE2 diario = new DiarioE2(pastaDeDados().resolve("operacoes-e2"));
        EditorDeArquivos editor = new EditorDeArquivos(config.pastaArquivos(), pastaDeDados(),
                arquivo, config.limitesEdicao());
        editor.recuperarPendentes(diario);
        if (config.computerUseAtivo()) ParadaLocalComputer.iniciar();
        ExecutorComputer computer = new ExecutorComputer();
        JanelaDaHud janela = new JanelaDaHud(config);
        EnergiaDoWindows energia = new EnergiaDoWindows();
        Despertador despertador = despertar(config, janela, energia, cliente);

        // O primeiro shutdown hook do projeto, e ele existe por um motivo concreto: o pedido de
        // energia ao Windows precisa ser desfeito. Sem isto, matar o agente deixaria o PC sem
        // suspender até o próximo logon.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (despertador != null) {
                despertador.close();
            }
            energia.close();
        }, "encerrando"));

        rodar(cliente, new ExecutorDeAcoes(config, janela, despertador), catalogo, diario,
                computer, config.computerUseAtivo(),
                new ExecutorDeRotinas(catalogo, java.util.stream.Stream.of(config.pcToken(),
                        System.getenv("ANTHROPIC_API_KEY"), System.getenv("CLAUDE_CODE_OAUTH_TOKEN"),
                        System.getenv("OPENAI_API_KEY"), System.getenv("GITHUB_TOKEN"))
                        .filter(java.util.Objects::nonNull).toList()), editor);
    }

    /**
     * Sobe a escuta do gatilho numa thread própria, ou devolve nulo se não há o que escutar.
     *
     * <p>Thread própria porque o laço principal fica 25 s parado em cada long poll — escutar
     * microfone ali dentro significaria ouvir a sala um segundo a cada meio minuto.
     */
    private static Despertador despertar(Configuracao config, JanelaDaHud janela,
            EnergiaDoWindows energia, ClienteJarvis cliente) {
        Despertador despertador =
                new Despertador(config, janela, energia, cliente::pedirFala);
        if (!despertador.temGatilho()) {
            log.warning("despertar sem gatilho nenhum de pé; seguindo só com os comandos da VPS");
        }
        Thread thread = new Thread(despertador, "despertador");
        thread.setDaemon(true);
        thread.start();
        return despertador;
    }

    /**
     * {@code --testar}: confere, sem falar com a VPS, as duas ações que dependem desta máquina.
     *
     * <p>Só as inofensivas — medir o PC e tirar um print. É o diagnóstico de "instalei e não
     * funciona": se a captura falhar aqui, o agente não está na sessão interativa do usuário.
     */
    private static void autotestar(Configuracao config) throws Exception {
        log.info("autoteste: status_pc -> " + StatusDoPc.medir());
        if (Captura.semTela()) {
            log.severe("autoteste: sem acesso à tela — o agente não está na sessão do usuário");
            return;
        }
        byte[] png = Captura.tirar();
        Path destino = pastaDeDados().resolve("autoteste.png");
        Files.createDirectories(destino.getParent());
        Files.write(destino, png);
        log.info("autoteste: captura_tela -> " + png.length / 1024 + " KB em " + destino);
        log.info("autoteste: apps que o Jarvis vai conhecer -> "
                + (config.apps().isEmpty() ? "nenhum" : config.cabecalhoDeApps()));
    }

    /**
     * O laço: busca, executa, responde.
     *
     * <p>A espera cresce a cada falha (1, 2, 4... até 60 s) e volta ao mínimo no primeiro sucesso.
     * A VPS reiniciando ou o Wi-Fi caindo não podem virar uma enxurrada de requisições — e um
     * agente que desiste e morre seria pior, porque voltaria só no próximo logon.
     */
    private static void rodar(ClienteJarvis cliente, ExecutorDeAcoes executor,
            CatalogoRotinas catalogo, DiarioE2 diario, ExecutorComputer computer,
            boolean computerAtivo,
            ExecutorDeRotinas rotinas, EditorDeArquivos editor)
            throws InterruptedException {
        Duration espera = ESPERA_INICIAL;
        while (true) {
            try {
                cliente.anunciarRotinas(catalogo);
                for (DiarioE2.Registro registro : diario.pendentes()) {
                    if (registro.estado().equals("executando") || registro.estado().equals("recebido")) {
                        diario.incerto(registro.id());
                        registro = diario.obter(registro.id());
                    }
                    if (cliente.enviarResultadoE2(registro)) diario.confirmarEnvio(registro.id());
                }
                diario.removerAntigos();
                ClienteJarvis.EntregaE2 entrega = cliente.buscarComandoE2();
                if (entrega != null) {
                    ComandoRecebido comando = entrega.comando();
                    if (java.time.Instant.now().isAfter(entrega.prazoInicio())) {
                        log.warning("comando E2 vencido antes do início: " + comando.id());
                        continue;
                    }
                    DiarioE2.Registro anterior = diario.obter(comando.id());
                    if (anterior != null && !anterior.estado().equals("recebido")) {
                        if (anterior.estado().equals("executando")) diario.incerto(comando.id());
                        if (!diario.obter(comando.id()).enviado()) {
                            if (cliente.enviarResultadoE2(diario.obter(comando.id())))
                                diario.confirmarEnvio(comando.id());
                        }
                        continue;
                    }
                    if (anterior == null) diario.recebido(comando.id());
                    if (!cliente.marcarE2(comando.id(), "recebido")) continue;
                    if (cliente.cancelamentoE2(comando.id())) {
                        diario.concluir(comando.id(), "cancelado", Resultado.falhou(comando,
                                "cancelado antes do início"), null);
                    } else if (diario.iniciar(comando.id()) && cliente.marcarE2(comando.id(), "executando")) {
                        if (!ACOES_E2.contains(comando.acao())) {
                            diario.concluir(comando.id(), "falhou", Resultado.falhou(comando,
                                    "ação E2 desconhecida"), null);
                        } else if (comando.acao().equals("computer_acao")) {
                            Resultado resultado = computerAtivo
                                    ? computer.executar(comando)
                                    : Resultado.falhou(comando, "computer use desativado no agente");
                            diario.concluir(comando.id(), resultado.ok() ? "concluído" : "falhou",
                                    resultado, null);
                        } else if (java.util.Set.of("preparar_edicao", "aplicar_edicao",
                                "preparar_desfazer", "aplicar_desfazer").contains(comando.acao())) {
                            Resultado resultado;
                            try {
                                resultado = switch (comando.acao()) {
                                    case "preparar_edicao" -> Resultado.comDados(comando,
                                            editor.preparar(comando.dados()));
                                    case "aplicar_edicao" -> editor.aplicar(comando);
                                    case "preparar_desfazer" -> Resultado.comDados(comando,
                                            editor.prepararDesfazer(comando.dados()));
                                    default -> editor.desfazer(comando);
                                };
                            } catch (Exception falha) {
                                resultado = Resultado.falhou(comando, erroDeEdicao(falha));
                            }
                            diario.concluir(comando.id(), resultado.ok() ? "concluído"
                                    : resultado.mensagem().startsWith("resultado_incerto")
                                            ? "resultado_incerto" : "falhou", resultado, null);
                        } else if (comando.acao().equals("rodar_rotina")) {
                            String pedido = comando.dados().path("pedido").asText("");
                            Rotina cadastrada = catalogo.obter(comando.argumento());
                            ExecutorDeRotinas.Saida saida = !comando.dados().path("pedido").isTextual()
                                    || pedido.length() > 8000 || pedido.isBlank()
                                    ? new ExecutorDeRotinas.Saida("falhou", null, "pedido original inválido")
                                    : cadastrada == null
                                    ? new ExecutorDeRotinas.Saida("falhou", null, "rotina desconhecida")
                                    : cadastrada.confirmar() && !comando.dados().path("confirmado").asBoolean(false)
                                    ? new ExecutorDeRotinas.Saida("falhou", null, "confirmação ausente")
                                    : rotinas.executar(comando.argumento(), pedido,
                                            () -> cliente.cancelamentoE2(comando.id()));
                            diario.concluir(comando.id(), saida.estado(),
                                    new Resultado(comando.id(), saida.estado().equals("concluído"),
                                            saida.texto(), null), saida.codigo());
                        } else {
                            Resultado resultado = executor.executar(comando);
                            diario.concluir(comando.id(), resultado.ok() ? "concluído" : "falhou", resultado, null);
                        }
                    } else {
                        diario.incerto(comando.id());
                    }
                    DiarioE2.Registro pronto = diario.obter(comando.id());
                    if (cliente.enviarResultadoE2(pronto)) diario.confirmarEnvio(comando.id());
                }
                espera = ESPERA_INICIAL;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("agente encerrando");
                return;
            } catch (Exception e) {
                // Nunca o token na mensagem: o ClienteJarvis já cuida de não o mencionar.
                log.warning("falha ao falar com a VPS: " + motivo(e)
                        + "; tento de novo em " + espera.toSeconds() + " s");
                Thread.sleep(espera.toMillis());
                espera = espera.multipliedBy(2).compareTo(ESPERA_MAXIMA) > 0
                        ? ESPERA_MAXIMA : espera.multipliedBy(2);
            }
        }
    }

    /**
     * O motivo da falha em texto útil.
     *
     * <p>Várias exceções de rede não têm mensagem — a de host inexistente, por exemplo —, e um log
     * dizendo só "null" não ajuda ninguém a descobrir se o problema é DNS, certificado ou token.
     */
    private static String motivo(Exception e) {
        String mensagem = e.getMessage();
        return mensagem == null || mensagem.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + mensagem;
    }

    private static String erroDeEdicao(Exception e) {
        String mensagem = e.getMessage();
        if (mensagem == null) return "edição recusada";
        for (String inicio : java.util.List.of("CONFLITO_", "resultado_incerto", "arquivo ",
                "pasta ", "diretório ", "backup ", "temporário ", "substituição ",
                "codificação ", "quebra ", "leia ", "reescrita ", "proposta ",
                "edição ", "extensão ", "campo ", "quantidade ", "pedido ",
                "versão ", "operação ", "resultado ", "cópia ", "nenhuma "))
            if (mensagem.startsWith(inicio)) return mensagem;
        return "não foi possível editar; arquivo ou disco indisponível";
    }

    /**
     * Log no console e num arquivo em {@code %LOCALAPPDATA%\jarvis-agente-pc}.
     *
     * <p>Arquivo porque o agente roda sem janela (javaw, pela tarefa agendada) e sem ele não
     * haveria onde ver por que um comando falhou.
     */
    private static void configurarLog() {
        Logger raiz = Logger.getLogger("");
        for (var antigo : raiz.getHandlers()) {
            raiz.removeHandler(antigo);
        }
        raiz.setLevel(Level.INFO);

        var formato = new SimpleFormatter() {
            @Override
            public synchronized String format(LogRecord registro) {
                return String.format("%1$tF %1$tT %2$-7s %3$s%n", registro.getMillis(),
                        registro.getLevel().getName(), registro.getMessage());
            }
        };
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(formato);
        raiz.addHandler(console);

        try {
            Path pasta = pastaDeDados();
            Files.createDirectories(pasta);
            FileHandler arquivo = new FileHandler(pasta.resolve("agente.log").toString(),
                    1024 * 1024, 3, true);
            arquivo.setFormatter(formato);
            raiz.addHandler(arquivo);
        } catch (IOException e) {
            console.publish(new LogRecord(Level.WARNING, "sem log em arquivo: " + e.getMessage()));
        }
    }

    private static Path pastaDeDados() {
        String local = System.getenv("LOCALAPPDATA");
        return local == null || local.isBlank()
                ? Path.of(System.getProperty("user.home"), ".jarvis-agente-pc")
                : Path.of(local, "jarvis-agente-pc");
    }
}
