package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ExecutorDaEmpresaTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @TempDir Path pasta;
    private HttpServer servidor;
    private volatile int statusDaExecucao = 200;
    private volatile String execucao = "{\"state\":\"working\"}";
    private ExecutorDaEmpresa executor;

    private record Fim(String estado, Resultado resultado) { }

    @BeforeEach void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/company/delegate", troca -> {
            troca.getRequestBody().readAllBytes();
            responder(troca, 200, "{\"runId\":\"r1\"}");
        });
        servidor.createContext("/company/runs/", troca -> responder(troca, statusDaExecucao, execucao));
        servidor.start();
        executor = criar(Duration.ofSeconds(20));
    }

    private static void responder(com.sun.net.httpserver.HttpExchange troca, int status, String texto) throws IOException {
        byte[] saida = texto.getBytes(StandardCharsets.UTF_8);
        troca.sendResponseHeaders(status, saida.length);
        troca.getResponseBody().write(saida);
        troca.close();
    }

    private ExecutorDaEmpresa criar(Duration prazo) {
        return new ExecutorDaEmpresa(new ClienteEmpresa(URI.create("http://127.0.0.1:"
                + servidor.getAddress().getPort()), "tok"), pasta, Duration.ofMillis(5), prazo);
    }

    @AfterEach void descer() {
        executor.close();
        servidor.stop(0);
    }

    private ComandoRecebido comando(String id, String pedido) {
        return new ComandoRecebido(id, "empresa_delegar", "", json.createObjectNode().put("pedido", pedido));
    }

    private Fim rodar(ComandoRecebido comando) throws Exception {
        CompletableFuture<Fim> fim = new CompletableFuture<>();
        assertTrue(executor.aceitar(comando, () -> false, (estado, resultado) -> fim.complete(new Fim(estado, resultado))));
        return fim.get(10, TimeUnit.SECONDS);
    }

    @Test void respeitaOIntervaloEOPrazoDoPlano() {
        assertEquals(Duration.ofSeconds(5), ExecutorDaEmpresa.INTERVALO);
        assertEquals(Duration.ofMinutes(55), ExecutorDaEmpresa.PRAZO);
    }

    @Test void resultadoCurtoVemSoNoResumo() throws Exception {
        execucao = "{\"state\":\"done\",\"text\":\"pronto\"}";
        Fim fim = rodar(comando("c1", "faça"));
        assertEquals("concluído", fim.estado());
        assertTrue(fim.resultado().ok());
        assertEquals("pronto", fim.resultado().dados().path("resumo").asText());
        assertNull(fim.resultado().arquivo());
    }

    @Test void resultadoLongoViraArquivoEMensagemCurta() throws Exception {
        String texto = "x".repeat(5000);
        execucao = "{\"state\":\"done\",\"text\":\"" + texto + "\"}";
        Fim fim = rodar(comando("c2", "faça"));
        assertNotNull(fim.resultado().arquivo());
        assertEquals(texto, Files.readString(fim.resultado().arquivo()));
        assertEquals(fim.resultado().arquivo().getFileName().toString(), fim.resultado().mensagem());
        assertTrue(fim.resultado().mensagem().matches("empresa-\\d{8}-\\d{4}(-\\d+)?\\.md"), fim.resultado().mensagem());
        assertTrue(fim.resultado().mensagem().length() <= 1000);
        assertTrue(fim.resultado().dados().path("resumo").asText().length() <= 900);
    }

    @Test void doisResultadosNoMesmoMinutoNaoSeSobrescrevem() throws Exception {
        execucao = "{\"state\":\"done\",\"text\":\"" + "y".repeat(1000) + "\"}";
        Path primeiro = rodar(comando("c3", "faça")).resultado().arquivo();
        execucao = "{\"state\":\"done\",\"text\":\"" + "z".repeat(1000) + "\"}";
        Path segundo = rodar(comando("c4", "faça")).resultado().arquivo();
        assertFalse(primeiro.equals(segundo));
        assertTrue(Files.readString(primeiro).startsWith("y"));
    }

    @Test void precisaDeAtencaoFalhaComOMotivo() throws Exception {
        execucao = "{\"state\":\"needs_attention\",\"reason\":\"O agente terminou sem entregar resposta.\",\"text\":\"\"}";
        Fim fim = rodar(comando("c5", "faça"));
        assertEquals("falhou", fim.estado());
        assertFalse(fim.resultado().ok());
        assertEquals("O agente terminou sem entregar resposta.", fim.resultado().mensagem());
    }

    @Test void motivoEnormeCabeNoLimiteDaVps() throws Exception {
        execucao = "{\"state\":\"needs_attention\",\"reason\":\"" + "m".repeat(4000) + "\",\"text\":\"\"}";
        assertTrue(rodar(comando("c6", "faça")).resultado().mensagem().length() <= 1000);
    }

    @Test void execucaoSumidaDizQueOHermes3dReiniciou() throws Exception {
        statusDaExecucao = 404;
        execucao = "{\"error\":\"not_found\"}";
        Fim fim = rodar(comando("c7", "faça"));
        assertEquals("falhou", fim.estado());
        assertEquals("O Hermes3D reiniciou durante a tarefa", fim.resultado().mensagem());
    }

    @Test void estourarOPrazoFalha() throws Exception {
        executor.close();
        executor = criar(Duration.ofMillis(30));
        Fim fim = rodar(comando("c8", "faça"));
        assertEquals("falhou", fim.estado());
        assertTrue(fim.resultado().mensagem().contains("prazo"), fim.resultado().mensagem());
    }

    @Test void adaptadorForaDoArAoDelegarFalha() throws Exception {
        servidor.stop(0);
        Fim fim = rodar(comando("c9", "faça"));
        assertEquals("falhou", fim.estado());
        assertEquals("Não consegui falar com o Hermes3D", fim.resultado().mensagem());
    }

    @Test void pedidoVazioFalhaSemDelegar() throws Exception {
        Fim fim = rodar(comando("c10", "  "));
        assertEquals("falhou", fim.estado());
    }

    @Test void cancelamentoEncerraAEspera() throws Exception {
        CompletableFuture<Fim> fim = new CompletableFuture<>();
        executor.aceitar(comando("c11", "faça"), () -> true, (estado, r) -> fim.complete(new Fim(estado, r)));
        assertEquals("cancelado", fim.get(10, TimeUnit.SECONDS).estado());
    }

    @Test void comTresEmVooRecusaAQuarta() throws Exception {
        CompletableFuture<Void> nunca = new CompletableFuture<>();
        for (String id : new String[] {"a", "b", "c"}) {
            assertTrue(executor.aceitar(comando(id, "faça"), () -> false, (e, r) -> nunca.complete(null)));
            assertTrue(executor.emVoo(id));
        }
        assertFalse(executor.aceitar(comando("d", "faça"), () -> false, (e, r) -> nunca.complete(null)));
        assertFalse(executor.emVoo("d"));
    }

    @Test void tarefaTerminadaSaiDoVoo() throws Exception {
        execucao = "{\"state\":\"done\",\"text\":\"ok\"}";
        rodar(comando("c12", "faça"));
        for (int i = 0; i < 200 && executor.emVoo("c12"); i++) Thread.sleep(5);
        assertFalse(executor.emVoo("c12"));
    }
}
