package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutorDeAcoesEmpresaTest {
    @TempDir Path pasta;
    private final List<URI> abertos = new ArrayList<>();

    private Configuracao config() {
        return new Configuracao(URI.create("https://api.test"), "t", URI.create("https://hud.test"), Map.of(),
                false, false, "", 12, .6, "app", false, pasta.resolve("w.exe"), pasta.resolve("m.bin"), pasta);
    }

    private ExecutorDeAcoes executor(AcoesDaEmpresa acoes) {
        return new ExecutorDeAcoes(config(), null, null, null, acoes);
    }

    private AcoesDaEmpresa acoes(ClienteEmpresa cliente, boolean portaAberta, Duration espera) {
        return new AcoesDaEmpresa(cliente, abertos::add, porta -> porta == 3000 && portaAberta, espera);
    }

    private ComandoRecebido comando(String acao, String argumento) {
        return new ComandoRecebido("c1", acao, argumento);
    }

    @Test void abrirUsaOEnderecoFixoEIgnoraOQueVemDaVps() throws Exception {
        Resultado r = executor(acoes(null, true, Duration.ofMillis(10)))
                .executar(comando("empresa_abrir", "https://malicioso.exemplo"));
        assertTrue(r.ok(), r.mensagem());
        assertEquals(List.of(URI.create("http://localhost:3000/office")), abertos);
    }

    @Test void abrirComOStudioDesligadoAvisaESaiSemAbrir() throws Exception {
        Resultado r = executor(acoes(null, false, Duration.ofMillis(10))).executar(comando("empresa_abrir", ""));
        assertFalse(r.ok());
        assertEquals("o escritório ainda está ligando", r.mensagem());
        assertTrue(abertos.isEmpty());
    }

    @Test void esperaPadraoEDeVinteSegundos() {
        assertEquals(Duration.ofSeconds(20), AcoesDaEmpresa.ESPERA_DO_STUDIO);
    }

    @Test void semHermes3dAsAcoesDizemIsso() throws Exception {
        ExecutorDeAcoes executor = new ExecutorDeAcoes(config(), null, null, null);
        for (String acao : List.of("empresa_abrir", "empresa_status")) {
            Resultado r = executor.executar(comando(acao, ""));
            assertFalse(r.ok());
            assertEquals("Hermes3D não configurado neste PC", r.mensagem());
        }
    }

    @Test void statusDevolveOsDadosDaRota() throws Exception {
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/company/status", troca -> {
            byte[] saida = "{\"working\":[{\"title\":\"açaí\"}]}".getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(200, saida.length);
            troca.getResponseBody().write(saida);
            troca.close();
        });
        servidor.start();
        try {
            ClienteEmpresa cliente = new ClienteEmpresa(URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()), "tok");
            Resultado r = executor(acoes(cliente, true, Duration.ofMillis(10))).executar(comando("empresa_status", ""));
            assertTrue(r.ok(), r.mensagem());
            assertEquals("açaí", r.dados().path("working").get(0).path("title").asText());
        } finally {
            servidor.stop(0);
        }
    }

    @Test void statusComAdaptadorForaDoArFalhaComFraseFixa() throws Exception {
        ClienteEmpresa cliente = new ClienteEmpresa(URI.create("http://127.0.0.1:9"), "tok");
        Resultado r = executor(acoes(cliente, true, Duration.ofMillis(10))).executar(comando("empresa_status", ""));
        assertFalse(r.ok());
        assertEquals("Não consegui falar com o Hermes3D", r.mensagem());
    }
}
