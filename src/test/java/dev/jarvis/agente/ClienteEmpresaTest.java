package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ClienteEmpresaTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> corpos = new ArrayList<>();
    private final List<String> autorizacoes = new ArrayList<>();
    private HttpServer servidor;
    private int status = 200;
    private String resposta = "{\"runId\":\"r1\"}";

    @BeforeEach void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            corpos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            autorizacoes.add(troca.getRequestHeaders().getFirst("Authorization"));
            byte[] saida = resposta.getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(status, saida.length);
            troca.getResponseBody().write(saida);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach void descer() { servidor.stop(0); }

    private ClienteEmpresa cliente() {
        return new ClienteEmpresa(URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()), "tok");
    }

    @Test void delegarEnviaOPedidoEmUtf8ComOToken() throws Exception {
        assertEquals("r1", cliente().delegar("r1", "campanha de açaí"));
        assertEquals("campanha de açaí", json.readTree(corpos.get(0)).path("message").asText());
        assertEquals("r1", json.readTree(corpos.get(0)).path("requestId").asText());
        assertEquals("Bearer tok", autorizacoes.get(0));
    }

    @Test void execucaoTraduzOEstado() throws Exception {
        resposta = "{\"state\":\"needs_attention\",\"reason\":\"sem resposta\",\"text\":\"\"}";
        ClienteEmpresa.Execucao execucao = cliente().execucao("r1");
        assertEquals("needs_attention", execucao.estado());
        assertEquals("sem resposta", execucao.motivo());
    }

    @Test void execucaoDesconhecidaViraEstadoProprio() throws Exception {
        status = 404;
        resposta = "{\"error\":\"not_found\"}";
        assertEquals(ClienteEmpresa.DESCONHECIDA, cliente().execucao("r1").estado());
    }

    @Test void statusDevolveOsDados() throws Exception {
        resposta = "{\"working\":[]}";
        assertEquals(true, cliente().status().has("working"));
    }

    @Test void respostaDeErroViraFalha() {
        status = 503;
        resposta = "{\"error\":\"fora\"}";
        assertThrows(IOException.class, () -> cliente().status());
    }
}
