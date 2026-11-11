package dev.jarvis.agente;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Mantém o Hermes3D de pé junto com o agente: backend, adaptador e Studio.
 *
 * <p>Quem manda é a porta, não o processo: porta aberta significa serviço no ar, mesmo que outra
 * pessoa o tenha ligado na mão, e aí não se sobe uma segunda cópia. O supervisor só encerra o que
 * ele mesmo iniciou.
 */
final class SupervisorHermes3d implements AutoCloseable {

    private static final Logger log = Logger.getLogger(SupervisorHermes3d.class.getName());
    private static final Duration ESPERA_INICIAL = Duration.ofSeconds(5);
    private static final Duration ESPERA_MAXIMA = Duration.ofMinutes(5);
    private static final int TEMPO_DE_CONEXAO_MS = 500;

    record Servico(String nome, int porta, List<String> comando, Path pasta, Map<String, String> ambiente) { }

    @FunctionalInterface
    interface Partida {
        Process iniciar(Servico servico) throws IOException;
    }

    @FunctionalInterface
    interface PortaAberta {
        boolean aberta(int porta);
    }

    private static final class Estado {
        Process processo;
        Instant proximaTentativa = Instant.MIN;
        Duration espera = ESPERA_INICIAL;
    }

    private final Partida partida;
    private final PortaAberta portaAberta;
    private final Clock relogio;
    private final Map<Servico, Estado> estados = new LinkedHashMap<>();
    private boolean encerrado;

    SupervisorHermes3d(Configuracao.ConfigHermes3d config, Partida partida, PortaAberta portaAberta, Clock relogio) {
        this.partida = partida;
        this.portaAberta = portaAberta;
        this.relogio = relogio;
        String node = config.node().toString();
        estados.put(new Servico("backend", 8642,
                List.of(config.hermes().toString(), "--profile", "default", "gateway", "run", "--quiet"),
                config.pasta(), Map.of()), new Estado());
        estados.put(new Servico("adaptador", 18789, List.of(node, "server/hermes-gateway-adapter.js"),
                config.pasta(), Map.of()), new Estado());
        estados.put(new Servico("studio", 3000, List.of(node, "server/index.js"),
                config.pasta(), Map.of("HOST", "127.0.0.1", "NODE_ENV", "production")), new Estado());
    }

    synchronized void verificar() {
        if (encerrado) return;
        estados.forEach(this::verificar);
    }

    private void verificar(Servico servico, Estado estado) {
        if (portaAberta.aberta(servico.porta())) {
            estado.espera = ESPERA_INICIAL;
            estado.proximaTentativa = Instant.MIN;
            return;
        }
        if (estado.processo != null && estado.processo.isAlive()) return;
        Instant agora = relogio.instant();
        if (agora.isBefore(estado.proximaTentativa)) return;
        estado.proximaTentativa = agora.plus(estado.espera);
        Duration dobro = estado.espera.multipliedBy(2);
        estado.espera = dobro.compareTo(ESPERA_MAXIMA) > 0 ? ESPERA_MAXIMA : dobro;
        try {
            estado.processo = partida.iniciar(servico);
            log.info("Hermes3D: iniciei " + servico.nome());
        } catch (IOException | RuntimeException falha) {
            log.warning("Hermes3D: não consegui iniciar " + servico.nome() + ": " + falha.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        encerrado = true;
        for (Estado estado : estados.values()) {
            Process processo = estado.processo;
            if (processo == null || !processo.isAlive()) continue;
            try {
                processo.descendants().forEach(ProcessHandle::destroy);
            } catch (UnsupportedOperationException semDescendentes) {
                log.fine("processo sem lista de descendentes");
            }
            processo.destroy();
        }
    }

    /** Sobe o serviço de verdade, com a saída num arquivo por serviço. */
    static Partida partidaReal(Path pastaDeLogs) {
        return servico -> {
            Files.createDirectories(pastaDeLogs);
            ProcessBuilder construtor = new ProcessBuilder(servico.comando())
                    .directory(servico.pasta().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            pastaDeLogs.resolve("hermes3d-" + servico.nome() + ".log").toFile()));
            construtor.environment().putAll(servico.ambiente());
            return construtor.start();
        };
    }

    static boolean portaLocalAberta(int porta) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", porta), TEMPO_DE_CONEXAO_MS);
            return true;
        } catch (IOException fechada) {
            return false;
        }
    }
}
