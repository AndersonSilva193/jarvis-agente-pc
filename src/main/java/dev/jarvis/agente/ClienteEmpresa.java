package dev.jarvis.agente;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fala com as rotas locais do adaptador do Hermes3D. Nada aqui sai da máquina. */
final class ClienteEmpresa {

    static final URI ENDERECO_PADRAO = URI.create("http://127.0.0.1:18789");
    /** A execução que o adaptador não conhece mais: ele reiniciou no meio da tarefa. */
    static final String DESCONHECIDA = "unknown";

    private static final Duration TEMPO_DE_CONEXAO = Duration.ofSeconds(3);
    private static final Duration TEMPO_DE_RESPOSTA = Duration.ofSeconds(15);

    record Execucao(String estado, String texto, String motivo) { }

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TEMPO_DE_CONEXAO).build();
    private final URI base;
    private final String token;

    ClienteEmpresa(URI base, String token) {
        this.base = base;
        this.token = token;
    }

    String delegar(String idDoPedido, String mensagem) throws IOException, InterruptedException {
        String corpo = json.writeValueAsString(
                json.createObjectNode().put("requestId", idDoPedido).put("message", mensagem));
        HttpRequest pedido = pedido("/company/delegate")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8))
                .build();
        return ler(pedido, enviar(pedido)).path("runId").asString();
    }

    Execucao execucao(String idDaExecucao) throws IOException, InterruptedException {
        HttpRequest pedido = pedido("/company/runs/" + idDaExecucao).GET().build();
        HttpResponse<String> resposta = enviar(pedido);
        if (resposta.statusCode() == 404) {
            return new Execucao(DESCONHECIDA, "", "");
        }
        JsonNode corpo = ler(pedido, resposta);
        return new Execucao(corpo.path("state").asString(""), corpo.path("text").asString(""),
                corpo.path("reason").asString(""));
    }

    void abortar(String idDaExecucao) throws IOException, InterruptedException {
        HttpRequest pedido = pedido("/company/runs/" + idDaExecucao + "/abort")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        ler(pedido, enviar(pedido));
    }

    JsonNode status() throws IOException, InterruptedException {
        HttpRequest pedido = pedido("/company/status").GET().build();
        return ler(pedido, enviar(pedido));
    }

    private HttpRequest.Builder pedido(String caminho) {
        return HttpRequest.newBuilder(base.resolve(caminho))
                .timeout(TEMPO_DE_RESPOSTA)
                .header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> enviar(HttpRequest pedido) throws IOException, InterruptedException {
        return http.send(pedido, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode ler(HttpRequest pedido, HttpResponse<String> resposta) throws IOException {
        if (resposta.statusCode() != 200) {
            throw new IOException(pedido.uri().getPath() + " respondeu " + resposta.statusCode());
        }
        try {
            return json.readTree(resposta.body());
        } catch (JacksonException invalido) {
            throw new IOException("resposta inválida do Hermes3D", invalido);
        }
    }
}
