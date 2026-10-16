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

    private static final Logger log = Logger.getLogger(AgentePc.class.getName());

    private static final Duration ESPERA_INICIAL = Duration.ofSeconds(1);
    private static final Duration ESPERA_MAXIMA = Duration.ofSeconds(60);

    private AgentePc() {
    }

    public static void main(String[] args) throws Exception {
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

        rodar(cliente, new ExecutorDeAcoes(config, janela, despertador));
    }

    /**
     * Sobe a escuta do gatilho numa thread própria, ou devolve nulo se não há o que escutar.
     *
     * <p>Thread própria porque o laço principal fica 25 s parado em cada long poll — escutar
     * microfone ali dentro significaria ouvir a sala um segundo a cada meio minuto.
     */
    private static Despertador despertar(Configuracao config, JanelaDaHud janela,
            EnergiaDoWindows energia, ClienteJarvis cliente) {
        if (!config.despertarLigado()) {
            log.info("despertar desligado (gatilho.palmas, gatilho.voz ou urlHud)");
            return null;
        }
        Despertador despertador =
                new Despertador(config, janela, energia, cliente::pedirFala);
        if (!despertador.temGatilho()) {
            log.warning("despertar sem gatilho nenhum de pé; seguindo só com os comandos da VPS");
            despertador.close();
            return null;
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
    private static void rodar(ClienteJarvis cliente, ExecutorDeAcoes executor)
            throws InterruptedException {
        Duration espera = ESPERA_INICIAL;
        while (true) {
            try {
                ComandoRecebido comando = cliente.buscarComando();
                if (comando != null) {
                    cliente.enviarResultado(executor.executar(comando));
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
