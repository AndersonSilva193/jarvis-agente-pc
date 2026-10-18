package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutorDeRotinasTest {
    @TempDir Path pasta;
    @Test void argumentosPermanecemSeparados() throws Exception {
        Path exe = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(exe));
        Rotina rotina = new Rotina("teste", "teste", pasta, exe,
                List.of("-version"), 3, List.of("leitura"), false, "executavel");
        assertEquals(List.of(exe.toString(), "-version"), ExecutorDeRotinas.comando(rotina));
    }
    @Test void cmdSoPeloAdaptadorFechado() throws Exception {
        Rotina rotina = new Rotina("maven", "maven", pasta, pasta.resolve("mvn.cmd"),
                List.of("-q", "-B", "test"), 3, List.of("executa_codigo"), true, "maven");
        List<String> comando = ExecutorDeRotinas.comando(rotina);
        assertEquals("/d", comando.get(1));
        assertEquals("/s", comando.get(2));
        assertEquals("/c", comando.get(3));
        assertThrows(java.io.IOException.class, () -> ExecutorDeRotinas.comando(new Rotina(
                "maven", "maven", pasta, pasta.resolve("mvn.cmd"), List.of("deploy"),
                3, List.of("publica"), true, "maven")));
    }

    private Rotina rotina(String modo, int timeout, String... adicionais) {
        Path exe = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        java.util.ArrayList<String> args = new java.util.ArrayList<>(List.of("-cp",
                System.getProperty("java.class.path"), Auxiliar.class.getName(), modo));
        args.addAll(List.of(adicionais));
        return new Rotina("teste", "teste", pasta, exe, List.copyOf(args), timeout,
                List.of("leitura"), false, "executavel");
    }

    @Test void saidaGrandeELimpezaDeSegredo() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(System.getProperty("java.home"), "bin", "java.exe")));
        var executor = new ExecutorDeRotinas(CatalogoRotinas.de(Map.of("teste", rotina("saida", 5))),
                List.of("segredo-de-teste"));
        var saida = executor.executar("teste", "", () -> false);
        assertEquals("concluído", saida.estado());
        assertTrue(saida.texto().contains("[REMOVIDO]"));
        assertFalse(saida.texto().contains("segredo-de-teste"));
        assertTrue(saida.texto().length() <= 900);
    }

    @Test void cancelamentoEExclusaoMutua() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(System.getProperty("java.home"), "bin", "java.exe")));
        var executor = new ExecutorDeRotinas(CatalogoRotinas.de(Map.of("teste", rotina("dormir", 8))), List.of());
        java.util.concurrent.atomic.AtomicBoolean parar = new java.util.concurrent.atomic.AtomicBoolean();
        try (var tarefas = Executors.newVirtualThreadPerTaskExecutor()) {
            var primeiro = tarefas.submit(() -> executor.executar("teste", "", parar::get));
            Thread.sleep(400);
            assertEquals("falhou", executor.executar("teste", "", () -> false).estado());
            parar.set(true);
            assertEquals("cancelado", primeiro.get(5, TimeUnit.SECONDS).estado());
        }
        AtomicInteger consultas = new AtomicInteger();
        var saida = executor.executar("teste", "", () -> consultas.incrementAndGet() > 2);
        assertEquals("cancelado", saida.estado());
    }

    @Test void timeoutEncerraFilho() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(System.getProperty("java.home"), "bin", "java.exe")));
        Path pid = pasta.resolve("filho.pid");
        var executor = new ExecutorDeRotinas(CatalogoRotinas.de(Map.of("teste",
                rotina("criar_filho", 2, pid.toString()))), List.of());
        var saida = executor.executar("teste", "", () -> false);
        assertEquals("falhou", saida.estado());
        assertTrue(Files.exists(pid));
        long identificador = Long.parseLong(Files.readString(pid));
        assertTrue(ProcessHandle.of(identificador).map(p -> !p.isAlive()).orElse(true));
    }

    public static class Auxiliar {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "saida" -> System.out.print("segredo-de-teste" + "x".repeat(100_000));
                case "dormir" -> Thread.sleep(Duration.ofSeconds(10));
                case "criar_filho" -> {
                    Process filho = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe")
                            .toString(), "-cp", System.getProperty("java.class.path"),
                            Auxiliar.class.getName(), "dormir").start();
                    Files.writeString(Path.of(args[1]), Long.toString(filho.pid()));
                    Thread.sleep(Duration.ofSeconds(10));
                }
                default -> throw new IllegalArgumentException();
            }
        }
    }
}
