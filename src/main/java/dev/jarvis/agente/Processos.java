package dev.jarvis.agente;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executa programa com <b>argumentos fixos</b>, nunca montando linha de shell.
 *
 * Isto não é preferência de estilo: o {@code ProcessBuilder} com lista de argumentos entrega cada
 * um ao programa como ele é, sem passar por interpretador. Concatenar uma string de {@code cmd} ou
 * de PowerShell com qualquer pedaço vindo da VPS seria injeção de comando — e a VPS recebe o que
 * um modelo de linguagem escreveu.
 *
 * Onde o PowerShell é inevitável (teclas de mídia e bateria), o {@code -Command} é uma
 * <b>constante</b> escolhida por {@code switch}, nunca algo concatenado.
 */
final class Processos {

    /** O que volta de um programa: o código de saída e o que ele falou. */
    record Saida(int codigo, String texto) {
        boolean ok() {
            return codigo == 0;
        }
    }

    private static final int MAX_TEXTO = 280;
    private static final int SEGUNDOS = 10;

    private Processos() {
    }

    static Saida rodar(String... argumentos) throws IOException, InterruptedException {
        Process processo = new ProcessBuilder(List.of(argumentos))
                .redirectErrorStream(true)
                .start();
        String texto;
        try (InputStream saida = processo.getInputStream()) {
            texto = new String(saida.readNBytes(4096), StandardCharsets.UTF_8).trim();
        }
        if (!processo.waitFor(SEGUNDOS, TimeUnit.SECONDS)) {
            processo.destroy();
            return new Saida(-1, "o programa não terminou em " + SEGUNDOS + " s");
        }
        return new Saida(processo.exitValue(), cortar(texto));
    }

    /** Dispara e não espera — para o que abre janela e fica aberto (navegador, editor, player). */
    static void disparar(String... argumentos) throws IOException {
        new ProcessBuilder(List.of(argumentos)).start();
    }

    /** Um PowerShell com um {@code -Command} que tem de ser constante. Ver o javadoc da classe. */
    static Saida powershell(String comandoConstante) throws IOException, InterruptedException {
        return rodar("powershell", "-NoProfile", "-NonInteractive", "-Command", comandoConstante);
    }

    /** A mensagem volta ao modelo e vira token, então o que o Windows falou vem cortado. */
    static String cortar(String texto) {
        String limpo = texto == null ? "" : texto.replaceAll("\\s+", " ").trim();
        return limpo.length() <= MAX_TEXTO ? limpo : limpo.substring(0, MAX_TEXTO) + "…";
    }
}
