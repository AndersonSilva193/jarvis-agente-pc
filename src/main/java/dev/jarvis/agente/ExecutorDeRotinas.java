package dev.jarvis.agente;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Executa apenas cadastros locais e encerra exclusivamente a árvore do processo iniciado. */
final class ExecutorDeRotinas {
    record Saida(String estado, Integer codigo, String texto) { }
    private static final int MAX_SAIDA = 900;
    private static final Map<Path, ReentrantLock> POR_PASTA = new ConcurrentHashMap<>();
    private final CatalogoRotinas catalogo;
    private final List<String> segredos;

    ExecutorDeRotinas(CatalogoRotinas catalogo, List<String> segredos) {
        this.catalogo = catalogo;
        this.segredos = segredos.stream().filter(s -> s != null && s.length() >= 4).toList();
    }
    Saida executar(String nome, String pedido, BooleanSupplier cancelar) throws InterruptedException {
        Rotina rotina = catalogo.obter(nome);
        if (rotina == null) return new Saida("falhou", null, "rotina desconhecida");
        ReentrantLock bloqueio = POR_PASTA.computeIfAbsent(rotina.pasta(), p -> new ReentrantLock());
        if (!bloqueio.tryLock()) return new Saida("falhou", null, "já há rotina em execução nesta pasta");
        try { return iniciar(rotina, pedido, cancelar); }
        finally { bloqueio.unlock(); }
    }
    private Saida iniciar(Rotina rotina, String pedido, BooleanSupplier cancelar) throws InterruptedException {
        if (cancelar.getAsBoolean()) return new Saida("cancelado", null, "cancelado antes do início");
        List<String> comando;
        try { comando = comando(rotina); }
        catch (IOException e) { return new Saida("falhou", null, "adaptador de programa inválido"); }
        ProcessBuilder builder = new ProcessBuilder(comando).directory(rotina.pasta().toFile())
                .redirectErrorStream(true);
        Process processo;
        try { processo = builder.start(); }
        catch (IOException e) { return new Saida("falhou", null, "não foi possível iniciar o programa"); }
        Coletor coletor = new Coletor(processo.getInputStream());
        Thread leitor = Thread.ofVirtual().name("saida-rotina-" + rotina.nome()).start(coletor);
        Thread escritor = Thread.ofVirtual().name("entrada-rotina-" + rotina.nome()).start(() -> {
            try (var entrada = processo.getOutputStream()) {
                if (rotina.tipo().equals("claude_leitura"))
                    entrada.write((pedido == null ? "" : pedido).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) { /* O processo pode encerrar antes de ler stdin. */ }
        });
        try {
            long fim = System.nanoTime() + Duration.ofSeconds(rotina.timeoutSegundos()).toNanos();
            while (processo.isAlive()) {
                if (cancelar.getAsBoolean()) {
                    boolean encerrado = encerrarArvore(processo);
                    return new Saida(encerrado ? "cancelado" : "resultado_incerto", null,
                            limpar(coletor.texto()));
                }
                if (System.nanoTime() >= fim) {
                    boolean encerrado = encerrarArvore(processo);
                    return new Saida(encerrado ? "falhou" : "resultado_incerto", null,
                            "tempo limite excedido; " + limpar(coletor.texto()));
                }
                processo.waitFor(250, TimeUnit.MILLISECONDS);
            }
            leitor.join(1000);
            escritor.join(1000);
            int codigo = processo.exitValue();
            return new Saida(codigo == 0 ? "concluído" : "falhou", codigo, limpar(coletor.texto()));
        } catch (InterruptedException e) {
            encerrarArvore(processo);
            throw e;
        }
    }
    static List<String> comando(Rotina rotina) throws IOException {
        if (rotina.tipo().equals("maven")) {
            String caminho = rotina.programa().toString();
            if (!caminho.matches("[A-Za-z0-9 :\\\\/._-]+") ||
                    !rotina.argumentos().equals(List.of("-q", "-B", "test")))
                throw new IOException("caminho ou opções Maven inseguros");
            String raiz = System.getenv("SystemRoot");
            if (raiz == null || raiz.isBlank()) raiz = "C:/Windows";
            return List.of(Path.of(raiz, "System32", "cmd.exe").toString(), "/d", "/s", "/c",
                    "\"\"" + caminho + "\" -q -B test\"");
        }
        if (rotina.tipo().equals("claude_leitura")) {
            return List.of(rotina.programa().toString(), "-p", "--safe-mode", "--restricted",
                    "--no-session-persistence",
                    "--permission-mode", "dontAsk", "--tools", "Read,Glob,Grep",
                    "--disallowedTools", "mcp__*", "--disable-slash-commands", "--no-chrome",
                    "--append-system-prompt",
                    "Analise em modo somente leitura o pedido recebido por stdin. Não altere arquivos.");
        }
        List<String> comando = new ArrayList<>();
        comando.add(rotina.programa().toString()); comando.addAll(rotina.argumentos());
        return List.copyOf(comando);
    }
    private static boolean encerrarArvore(Process processo) {
        List<ProcessHandle> filhos = processo.descendants().toList();
        for (ProcessHandle filho : filhos.reversed()) filho.destroy();
        processo.destroy();
        try { processo.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        for (ProcessHandle filho : filhos.reversed()) if (filho.isAlive()) filho.destroyForcibly();
        if (processo.isAlive()) processo.destroyForcibly();
        try { processo.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return !processo.isAlive() && filhos.stream().noneMatch(ProcessHandle::isAlive);
    }
    private String limpar(String texto) {
        String limpo = texto.replaceAll("[\\p{Cntrl}&&[^\\t\\n]]", " ");
        for (String segredo : segredos) limpo = limpo.replace(segredo, "[REMOVIDO]");
        return limpo.length() > MAX_SAIDA ? limpo.substring(0, MAX_SAIDA) : limpo;
    }
    private static final class Coletor implements Runnable {
        private final InputStream entrada;
        private final StringBuilder texto = new StringBuilder();
        Coletor(InputStream entrada) { this.entrada = entrada; }
        @Override public void run() {
            try (InputStream origem = entrada) {
                byte[] bloco = new byte[4096]; int n;
                while ((n = origem.read(bloco)) >= 0) {
                    synchronized (texto) {
                        if (texto.length() < MAX_SAIDA)
                            texto.append(new String(bloco, 0, Math.min(n, MAX_SAIDA - texto.length()),
                                    StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignorada) { }
        }
        String texto() { synchronized (texto) { return texto.toString(); } }
    }
}
