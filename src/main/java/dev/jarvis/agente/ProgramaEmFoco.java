package dev.jarvis.agente;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * O cabeçalho {@code X-Pc-Foco}: o <b>nome do processo</b> em primeiro plano (chrome, Code,
 * WINWORD), para a VPS saber em que programa o usuário está.
 *
 * <p>Nunca o título da janela: ele carrega nome de arquivo, assunto de e-mail e texto de página.
 * Por isso a interface do user32 aqui nem declara {@code GetWindowTextW} — o título não tem por
 * onde entrar. O nome vem do caminho do executável, pela API do Windows, só leitura e sem abrir
 * processo nenhum (nada de PowerShell, nada de linha de shell).
 *
 * <p>Qualquer falha, ou nome fora de {@code [A-Za-z0-9._-]{1,40}}, é cabeçalho ausente.
 */
final class ProgramaEmFoco {

    /** Devolve o caminho (ou só o nome) do executável em primeiro plano; nulo quando não há. */
    interface Leitor {
        String caminhoDoExecutavel() throws Exception;
    }

    /** As funções do Windows usadas, todas de leitura. Carregadas só no primeiro uso. */
    private static final class Api {
        interface User32 extends StdCallLibrary {
            Pointer GetForegroundWindow();
            int GetWindowThreadProcessId(Pointer janela, IntByReference pid);
        }
        interface Kernel32 extends StdCallLibrary {
            Pointer OpenProcess(int acesso, boolean herdar, int pid);
            boolean QueryFullProcessImageNameW(Pointer processo, int flags, char[] nome, IntByReference tamanho);
            boolean CloseHandle(Pointer handle);
        }
        static final User32 USER = Native.load("user32", User32.class);
        static final Kernel32 KERNEL = Native.load("kernel32", Kernel32.class);
    }

    /** Basta para ler o nome da imagem, inclusive de processo elevado; não dá acesso à memória. */
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    static final Leitor WINDOWS = () -> {
        Pointer janela = Api.USER.GetForegroundWindow();
        if (janela == null) return null;
        IntByReference pid = new IntByReference();
        Api.USER.GetWindowThreadProcessId(janela, pid);
        if (pid.getValue() == 0) return null;
        Pointer processo = Api.KERNEL.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.getValue());
        if (processo == null) return null;
        try {
            char[] nome = new char[1024];
            IntByReference tamanho = new IntByReference(nome.length);
            if (!Api.KERNEL.QueryFullProcessImageNameW(processo, 0, nome, tamanho)) return null;
            return new String(nome, 0, Math.min(tamanho.getValue(), nome.length));
        } finally {
            Api.KERNEL.CloseHandle(processo);
        }
    };

    static final Duration CACHE = Duration.ofSeconds(30);
    private static final Pattern NOME = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private final boolean ativo;
    private final Leitor leitor;
    private final Clock relogio;

    private String medida;
    private Instant medidaEm;

    ProgramaEmFoco(boolean ativo, Leitor leitor, Clock relogio) {
        this.ativo = ativo;
        this.leitor = leitor;
        this.relogio = relogio;
    }

    /** O valor do cabeçalho, ou nulo para não mandá-lo. Nunca lança. */
    synchronized String cabecalho() {
        if (!ativo) return null;
        Instant agora = relogio.instant();
        if (medidaEm == null || !agora.isBefore(medidaEm.plus(CACHE))) {
            medida = medir();
            medidaEm = agora;
        }
        return medida;
    }

    private String medir() {
        try {
            return higienizar(leitor.caminhoDoExecutavel());
        } catch (Throwable e) {
            // Inclui UnsatisfiedLinkError: sem user32, o agente segue sem o cabeçalho.
            return null;
        }
    }

    /**
     * Do caminho do executável ao nome do processo: o trecho depois da última barra, sem o
     * {@code .exe}. Só passa se sobrar {@code [A-Za-z0-9._-]{1,40}} — o que não cabe é recusado
     * inteiro, nunca filtrado até caber: um nome mutilado diria à VPS um programa que não existe.
     */
    static String higienizar(String bruto) {
        if (bruto == null) return null;
        String nome = bruto.substring(Math.max(bruto.lastIndexOf('\\'), bruto.lastIndexOf('/')) + 1);
        if (nome.toLowerCase(Locale.ROOT).endsWith(".exe")) nome = nome.substring(0, nome.length() - 4);
        return valido(nome) ? nome : null;
    }

    /** Se o valor pode ir no cabeçalho como está. */
    static boolean valido(String nome) {
        return nome != null && NOME.matcher(nome).matches();
    }
}
