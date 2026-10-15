package dev.jarvis.agente;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A conversa com a VPS: busca comando e posta resultado.
 *
 * Só chamadas de saída. Nada escuta porta neste PC — é essa a ideia inteira do agente.
 */
final class ClienteJarvis {

    private static final Logger log = Logger.getLogger(ClienteJarvis.class.getName());

    /** O servidor segura o GET por até 25 s; 40 s é folga para a resposta chegar. */
    private static final Duration TIMEOUT_BUSCA = Duration.ofSeconds(40);
    private static final Duration TIMEOUT_RESULTADO = Duration.ofSeconds(30);
    /** Tentativas de postar o resultado: perder um print por soluço de rede seria uma pena. */
    private static final int TENTATIVAS_RESULTADO = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Configuracao config;

    ClienteJarvis(Configuracao config) {
        this.config = config;
    }

    /**
     * O próximo comando, ou {@code null} quando o servidor devolve 204 (nada pendente).
     *
     * @throws IOException quando a VPS não responde ou recusa — quem chama faz a espera crescente
     */
    ComandoRecebido buscarComando() throws IOException, InterruptedException {
        HttpRequest requisicao = autenticar(HttpRequest.newBuilder(uri("/api/pc/comandos")))
                .timeout(TIMEOUT_BUSCA)
                // A lista de apps vai em toda consulta: é assim que a VPS monta o enum de
                // abrir_app sem guardar cópia dela.
                .header("X-Pc-Apps", config.cabecalhoDeApps())
                .GET()
                .build();

        HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() == 204) {
            return null;
        }
        if (resposta.statusCode() != 200) {
            throw new IOException("a VPS respondeu HTTP " + resposta.statusCode()
                    + " em /api/pc/comandos" + (resposta.statusCode() == 401
                            ? " (pcToken errado ou PC_TOKEN vazio no .env da VPS)" : ""));
        }
        JsonNode raiz = json.readTree(resposta.body());
        String id = texto(raiz, "id");
        String acao = texto(raiz, "acao");
        if (id.isEmpty() || acao.isEmpty()) {
            throw new IOException("comando sem id ou sem acao");
        }
        return new ComandoRecebido(id, acao, texto(raiz, "argumento"));
    }

    /** Posta o resultado, tentando de novo quando a rede falha. */
    void enviarResultado(Resultado resultado) throws InterruptedException {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("comandoId", resultado.comandoId());
        corpo.put("ok", resultado.ok());
        corpo.put("mensagem", resultado.mensagem());
        if (resultado.imagemBase64() != null) {
            corpo.put("imagemBase64", resultado.imagemBase64());
        }
        String texto = json.writeValueAsString(corpo);

        for (int tentativa = 1; tentativa <= TENTATIVAS_RESULTADO; tentativa++) {
            try {
                HttpRequest requisicao = autenticar(HttpRequest.newBuilder(uri("/api/pc/resultado")))
                        .timeout(TIMEOUT_RESULTADO)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(texto))
                        .build();
                HttpResponse<String> resposta =
                        http.send(requisicao, HttpResponse.BodyHandlers.ofString());
                if (resposta.statusCode() == 204) {
                    return;
                }
                // 404 é comando que já venceu na VPS; insistir não traz ele de volta.
                log.warning("a VPS recusou o resultado com HTTP " + resposta.statusCode()
                        + ": " + resposta.body());
                if (resposta.statusCode() < 500) {
                    return;
                }
            } catch (IOException e) {
                log.warning("tentativa " + tentativa + " de postar o resultado falhou: " + e.getMessage());
            }
            if (tentativa < TENTATIVAS_RESULTADO) {
                Thread.sleep(Duration.ofSeconds(2L * tentativa).toMillis());
            }
        }
    }

    private HttpRequest.Builder autenticar(HttpRequest.Builder requisicao) {
        // No cabeçalho, nunca na query: na query o token iria para o log do nginx.
        return requisicao.header("Authorization", "Bearer " + config.pcToken());
    }

    private URI uri(String rota) {
        return URI.create(config.urlApi() + rota);
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        return valor.isTextual() ? valor.stringValue().trim() : "";
    }
}
