package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class SupervisorHermes3dTest {

    private static final class ProcessoFalso extends Process {
        boolean vivo = true;
        boolean encerrado;
        boolean descendentesConsultados;

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public boolean isAlive() { return vivo; }
        @Override public void destroy() { vivo = false; encerrado = true; }
        @Override public Stream<ProcessHandle> descendants() {
            descendentesConsultados = true;
            return Stream.empty();
        }
    }

    private static final class RelogioAjustavel extends Clock {
        Instant agora = Instant.parse("2026-11-11T10:00:00Z");
        void avancar(Duration tempo) { agora = agora.plus(tempo); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return agora; }
    }

    private final Set<Integer> portasAbertas = new HashSet<>();
    private final List<SupervisorHermes3d.Servico> partidas = new ArrayList<>();
    private final List<ProcessoFalso> processos = new ArrayList<>();
    private final RelogioAjustavel relogio = new RelogioAjustavel();
    private final Configuracao.ConfigHermes3d config = new Configuracao.ConfigHermes3d(true,
            Path.of("C:/h3d"), Path.of("C:/node.exe"), Path.of("C:/hermes.exe"), "tok");

    private SupervisorHermes3d supervisor() {
        return new SupervisorHermes3d(config, servico -> {
            partidas.add(servico);
            ProcessoFalso processo = new ProcessoFalso();
            processos.add(processo);
            return processo;
        }, portasAbertas::contains, relogio);
    }

    private SupervisorHermes3d.Servico partida(String nome) {
        return partidas.stream().filter(s -> s.nome().equals(nome)).findFirst().orElseThrow();
    }

    @Test void comTudoFechadoPartePelaOrdemDoBackendAoStudio() {
        supervisor().verificar();
        assertEquals(List.of("backend", "adaptador", "studio"),
                partidas.stream().map(SupervisorHermes3d.Servico::nome).toList());
        assertEquals(List.of(8642, 18789, 3000),
                partidas.stream().map(SupervisorHermes3d.Servico::porta).toList());
    }

    @Test void portaAbertaNaoParteOServico() {
        portasAbertas.addAll(List.of(8642, 3000));
        supervisor().verificar();
        assertEquals(List.of("adaptador"), partidas.stream().map(SupervisorHermes3d.Servico::nome).toList());
    }

    @Test void comandoDoBackendUsaOPerfilPadrao() {
        supervisor().verificar();
        assertEquals(List.of("C:\\hermes.exe".replace('\\', java.io.File.separatorChar),
                "--profile", "default", "gateway", "run", "--quiet"), partida("backend").comando());
    }

    @Test void adaptadorRodaNaPastaDoHermes3d() {
        supervisor().verificar();
        assertEquals("server/hermes-gateway-adapter.js", partida("adaptador").comando().get(1));
        assertEquals(Path.of("C:/h3d"), partida("adaptador").pasta());
    }

    @Test void studioFicaSoNoLocalEmProducao() {
        supervisor().verificar();
        assertEquals("server/index.js", partida("studio").comando().get(1));
        assertEquals(Map.of("HOST", "127.0.0.1", "NODE_ENV", "production", "UPSTREAM_ALLOWLIST", "localhost,127.0.0.1"),
                partida("studio").ambiente());
    }

    @Test void processoVivoAindaSubindoNaoGanhaCopia() {
        SupervisorHermes3d supervisor = supervisor();
        supervisor.verificar();
        relogio.avancar(Duration.ofMinutes(10));
        supervisor.verificar();
        assertEquals(3, partidas.size());
    }

    @Test void depoisDeCairEsperaCadaVezMaisAteCincoMinutos() {
        portasAbertas.addAll(List.of(8642, 3000));
        SupervisorHermes3d supervisor = supervisor();
        supervisor.verificar();
        for (int esperado : new int[] {5, 10, 20, 40, 80, 160, 300, 300}) {
            int antes = partidas.size();
            processos.getLast().vivo = false;
            relogio.avancar(Duration.ofSeconds(esperado - 1));
            supervisor.verificar();
            assertEquals(antes, partidas.size(), "cedo demais para " + esperado);
            relogio.avancar(Duration.ofSeconds(1));
            supervisor.verificar();
            assertEquals(antes + 1, partidas.size(), "devia ter partido após " + esperado);
        }
    }

    @Test void portaAbertaZeraAEspera() {
        portasAbertas.addAll(List.of(8642, 3000));
        SupervisorHermes3d supervisor = supervisor();
        supervisor.verificar();
        processos.getLast().vivo = false;
        relogio.avancar(Duration.ofSeconds(5));
        supervisor.verificar();
        portasAbertas.add(18789);
        supervisor.verificar();
        portasAbertas.remove(18789);
        processos.getLast().vivo = false;
        int antes = partidas.size();
        relogio.avancar(Duration.ofSeconds(5));
        supervisor.verificar();
        assertEquals(antes + 1, partidas.size());
    }

    @Test void falhaAoPartirUmServicoNaoImpedeOsOutros() {
        List<String> tentados = new ArrayList<>();
        new SupervisorHermes3d(config, servico -> {
            tentados.add(servico.nome());
            if (servico.nome().equals("backend")) throw new IOException("sem exe");
            return new ProcessoFalso();
        }, porta -> false, relogio).verificar();
        assertEquals(List.of("backend", "adaptador", "studio"), tentados);
    }

    @Test void closeEncerraSoOQueEleIniciouIncluindoDescendentes() {
        portasAbertas.add(8642);
        SupervisorHermes3d supervisor = supervisor();
        supervisor.verificar();
        supervisor.close();
        assertEquals(2, processos.size());
        assertTrue(processos.stream().allMatch(p -> p.encerrado && p.descendentesConsultados));
    }

    @Test void depoisDoCloseNaoParteMais() {
        SupervisorHermes3d supervisor = supervisor();
        supervisor.close();
        supervisor.verificar();
        assertFalse(partidas.stream().findAny().isPresent());
    }
}
