package dev.jarvis.agente;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;

import dev.jarvis.agente.gatilho.Despertador;
import dev.jarvis.agente.gatilho.JanelaDaHud;

/**
 * Traduz a ação que a VPS mandou em um programa do Windows.
 *
 * Duas regras valem para tudo aqui:
 *
 * <ol>
 *   <li><b>Argumentos fixos, sempre</b> (ver {@link Processos}): nenhuma linha de shell é montada
 *       com pedaço vindo da VPS.</li>
 *   <li><b>Revalidar o que a VPS já validou.</b> O servidor também confere que a URL é https e
 *       que o app está na lista, mas este processo é o que tem o poder de verdade na máquina —
 *       confiar na checagem do outro lado é o que transforma um bug de lá num problema aqui.</li>
 * </ol>
 */
final class ExecutorDeAcoes {

    private static final Logger log = Logger.getLogger(ExecutorDeAcoes.class.getName());

    /** Segundos antes do desligamento. É a janela para desistir com cancelar_desligamento. */
    private static final String ESPERA_SHUTDOWN = "60";
    /** O shutdown /a devolve isto quando não havia nada agendado. */
    private static final int NADA_AGENDADO = 1116;

    // Os três -Command do volume, constantes: 175 sobe, 174 abaixa e 173 alterna o mudo.
    private static final String VOLUME_SUBIR =
            "(New-Object -ComObject WScript.Shell).SendKeys([char]175)";
    private static final String VOLUME_BAIXAR =
            "(New-Object -ComObject WScript.Shell).SendKeys([char]174)";
    private static final String VOLUME_MUDO =
            "(New-Object -ComObject WScript.Shell).SendKeys([char]173)";

    private final Configuracao config;
    private final JanelaDaHud janela;
    /** Nulo quando o despertar está desligado: aí as ações dele respondem que não há o que fazer. */
    private final Despertador despertador;

    ExecutorDeAcoes(Configuracao config, JanelaDaHud janela, Despertador despertador) {
        this.config = config;
        this.janela = janela;
        this.despertador = despertador;
    }

    Resultado executar(ComandoRecebido comando) throws InterruptedException {
        log.info("executando '" + comando.acao() + "'");
        try {
            return switch (comando.acao()) {
                case "abrir_app" -> abrirApp(comando);
                case "abrir_url" -> abrirUrl(comando);
                case "abrir_hud" -> abrirHud(comando);
                case "bloquear_tela" -> bloquearTela(comando);
                case "volume" -> volume(comando);
                case "captura_tela" -> capturaTela(comando);
                case "status_pc" -> Resultado.feito(comando, StatusDoPc.medir());
                case "desligar" -> agendarShutdown(comando, "/s", "desligar");
                case "reiniciar" -> agendarShutdown(comando, "/r", "reiniciar");
                case "cancelar_desligamento" -> cancelarDesligamento(comando);
                case "modo_repouso" -> modoRepouso(comando);
                case "falso_positivo" -> falsoPositivo(comando);
                default -> Resultado.falhou(comando, "não conheço a ação '" + comando.acao() + "'");
            };
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warning("ação '" + comando.acao() + "' falhou: " + e);
            return Resultado.falhou(comando, Processos.cortar(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private Resultado abrirApp(ComandoRecebido comando) throws Exception {
        Path executavel = config.app(comando.argumento());
        if (executavel == null) {
            // A lista é deste PC, e o nome fora dela não abre nada, venha de onde vier.
            return Resultado.falhou(comando, "'" + comando.argumento()
                    + "' não está na lista de apps deste PC");
        }
        Processos.disparar(executavel.toString());
        return Resultado.feito(comando, "abri o " + comando.argumento().toLowerCase(Locale.ROOT));
    }

    private Resultado abrirUrl(ComandoRecebido comando) throws Exception {
        URI uri = httpsValida(comando.argumento());
        if (uri == null) {
            return Resultado.falhou(comando, "só abro endereço https");
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return Resultado.falhou(comando, "este Windows não deixa o agente abrir o navegador");
        }
        Desktop.getDesktop().browse(uri);
        return Resultado.feito(comando, "abri no navegador");
    }

    /**
     * Abre a tela do Jarvis.
     *
     * <p>Delega à {@link JanelaDaHud}, a mesma que o despertar por palmas usa. Dois lugares
     * abrindo o Chrome com conjuntos de flags diferentes acabariam divergindo — e o perfil
     * próprio, que é o que deixa o agente saber quando a janela fecha, é fácil de esquecer.
     */
    private Resultado abrirHud(ComandoRecebido comando) throws Exception {
        if (config.urlHud() == null) {
            return Resultado.falhou(comando, "falta urlHud no config.properties deste PC");
        }
        if (config.app("chrome") == null) {
            return Resultado.falhou(comando, "falta app.chrome no config.properties deste PC");
        }
        return janela.abrir()
                ? Resultado.feito(comando, "abri a HUD em tela cheia")
                : Resultado.feito(comando, "a HUD já estava aberta; trouxe para a frente");
    }

    /** O "Jarvis, modo repouso": fecha a tela e o agente volta a escutar o gatilho. */
    private Resultado modoRepouso(ComandoRecebido comando) {
        if (despertador == null) {
            return Resultado.falhou(comando, "o despertar por palmas está desligado neste PC");
        }
        despertador.repousar();
        return Resultado.feito(comando, "voltando ao repouso; escutando o gatilho de novo");
    }

    /** O "Jarvis, foi engano": conta o alarme falso e devolve o total. */
    private Resultado falsoPositivo(ComandoRecebido comando) {
        if (despertador == null) {
            return Resultado.falhou(comando, "o despertar por palmas está desligado neste PC");
        }
        int total = despertador.falsoPositivo();
        return Resultado.feito(comando, total == 1
                ? "anotado: 1 alarme falso desde que liguei"
                : "anotado: " + total + " alarmes falsos desde que liguei");
    }

    private Resultado bloquearTela(ComandoRecebido comando) throws Exception {
        Processos.Saida saida = Processos.rodar("rundll32.exe", "user32.dll,LockWorkStation");
        return saida.ok()
                ? Resultado.feito(comando, "tela bloqueada")
                : Resultado.falhou(comando, "não consegui bloquear: " + saida.texto());
    }

    private Resultado volume(ComandoRecebido comando) throws Exception {
        String pedido = comando.argumento().toLowerCase(Locale.ROOT);
        String tecla = switch (pedido) {
            case "subir" -> VOLUME_SUBIR;
            case "baixar" -> VOLUME_BAIXAR;
            case "mudo" -> VOLUME_MUDO;
            default -> null;
        };
        if (tecla == null) {
            return Resultado.falhou(comando, "volume aceita subir, baixar ou mudo");
        }
        Processos.Saida saida = Processos.powershell(tecla);
        if (!saida.ok()) {
            return Resultado.falhou(comando, "não consegui mexer no volume: " + saida.texto());
        }
        return Resultado.feito(comando, switch (pedido) {
            case "subir" -> "volume aumentado";
            case "baixar" -> "volume reduzido";
            default -> "mudo alternado";
        });
    }

    private Resultado capturaTela(ComandoRecebido comando) throws Exception {
        if (Captura.semTela()) {
            return Resultado.falhou(comando, SEM_SESSAO);
        }
        try {
            byte[] png = Captura.tirar();
            return Resultado.comImagem(comando, "print de " + png.length / 1024 + " KB", png);
        } catch (java.awt.AWTException e) {
            return Resultado.falhou(comando, SEM_SESSAO);
        }
    }

    private static final String SEM_SESSAO = "não consigo ver a tela: o agente não está na sessão "
            + "do usuário (ele precisa iniciar no logon, não como serviço)";

    private Resultado agendarShutdown(ComandoRecebido comando, String opcao, String verbo)
            throws Exception {
        Processos.Saida saida = Processos.rodar("shutdown", opcao, "/t", ESPERA_SHUTDOWN);
        if (!saida.ok()) {
            return Resultado.falhou(comando, "o Windows recusou: " + saida.texto());
        }
        log.warning("vou " + verbo + " em " + ESPERA_SHUTDOWN + " s");
        return Resultado.feito(comando, "vou " + verbo + " em " + ESPERA_SHUTDOWN
                + " segundos; ainda dá para desistir com cancelar_desligamento");
    }

    private Resultado cancelarDesligamento(ComandoRecebido comando) throws Exception {
        Processos.Saida saida = Processos.rodar("shutdown", "/a");
        if (saida.ok()) {
            return Resultado.feito(comando, "desligamento cancelado");
        }
        // "Nada agendado" não é falha: o usuário pediu para cancelar e não há o que cancelar.
        if (saida.codigo() == NADA_AGENDADO) {
            return Resultado.feito(comando, "não havia desligamento agendado");
        }
        return Resultado.falhou(comando, "não consegui cancelar: " + saida.texto());
    }

    /** A mesma checagem que a VPS faz, repetida aqui de propósito. Nulo = recusar. */
    private static URI httpsValida(String endereco) {
        if (endereco == null || endereco.isBlank() || endereco.matches(".*[\\s\\p{Cntrl}].*")) {
            return null;
        }
        try {
            URI uri = URI.create(endereco);
            boolean boa = uri.isAbsolute() && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null;
            return boa ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
