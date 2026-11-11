package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Um resultado que a VPS nunca vai aceitar não pode parar o laço do agente. */
class ClienteJarvisResultadoTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> resultados = new ArrayList<>();
    @TempDir Path pasta;
    private HttpServer servidor;
    private volatile int statusDoUpload = 204;

    @BeforeEach void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/api/pc/arquivos/", troca -> {
            troca.getRequestBody().readAllBytes();
            troca.sendResponseHeaders(statusDoUpload, -1);
            troca.close();
        });
        servidor.createContext("/api/pc/v2/comandos/", troca -> {
            resultados.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            troca.sendResponseHeaders(204, -1);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach void descer() { servidor.stop(0); }

    private ClienteJarvis cliente() {
        return new ClienteJarvis(new Configuracao(URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()),
                "segredo", null, Map.of(), false, false, "", 18, 0.6, "app", true));
    }

    private DiarioE2.Registro registro(String acao, Path arquivo, JsonNode dados) {
        return new DiarioE2.Registro("c1", "concluído", arquivo.getFileName().toString(), null, "", arquivo.toString(),
                dados, Instant.now(), false, acao);
    }

    private JsonNode resumo() {
        return json.createObjectNode().put("resumo", "Quatro conteúdos.");
    }

    private Path arquivo() throws IOException {
        return Files.writeString(pasta.resolve("empresa-1.md"), "completo");
    }

    private void confereEntregaSemArquivo() {
        JsonNode corpo = json.readTree(resultados.get(0));
        assertFalse(corpo.path("arquivo").asBoolean(true));
        assertEquals("salvo, entrega falhou: empresa-1.md", corpo.path("mensagem").asString());
        assertEquals("Quatro conteúdos.", corpo.path("dados").path("resumo").asString());
    }

    @Test void uploadRecusadoDaEmpresaAindaEntregaOResumo() throws Exception {
        statusDoUpload = 409;
        assertTrue(cliente().enviarResultadoE2(registro("empresa_delegar", arquivo(), resumo())));
        confereEntregaSemArquivo();
    }

    @Test void arquivoSumidoDaEmpresaAindaEntregaOResumo() throws Exception {
        Path sumido = pasta.resolve("empresa-1.md");
        assertTrue(cliente().enviarResultadoE2(registro("empresa_delegar", sumido, resumo())));
        confereEntregaSemArquivo();
    }

    @Test void uploadComErroPassageiroTentaDeNovo() throws Exception {
        statusDoUpload = 503;
        IOException falha = assertThrows(IOException.class,
                () -> cliente().enviarResultadoE2(registro("empresa_delegar", arquivo(), resumo())));
        assertFalse(falha instanceof RecusaDefinitiva);
        assertTrue(resultados.isEmpty());
    }

    @Test void uploadRecusadoDeOutraAcaoDesisteDoResultado() throws Exception {
        statusDoUpload = 409;
        assertThrows(RecusaDefinitiva.class,
                () -> cliente().enviarResultadoE2(registro("criar_arquivo", arquivo(), null)));
    }

    @Test void arquivoSumidoDeOutraAcaoDesisteDoResultado() {
        assertThrows(RecusaDefinitiva.class, () -> cliente().enviarResultadoE2(
                registro("criar_arquivo", pasta.resolve("nao-existe.md"), null)));
    }

    @Test void dadosAcimaDoLimiteDesistemDoResultado() throws Exception {
        JsonNode enormes = json.createObjectNode().put("x", "y".repeat(40_000));
        DiarioE2.Registro registro = new DiarioE2.Registro("c1", "concluído", "ok", null, "", "", enormes,
                Instant.now(), false, "empresa_status");
        assertThrows(RecusaDefinitiva.class, () -> cliente().enviarResultadoE2(registro));
    }
}
