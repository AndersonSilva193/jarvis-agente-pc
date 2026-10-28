package dev.jarvis.agente;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    record EntregaE2(ComandoRecebido comando, Instant prazoInicio, Instant prazoExecucao) { }

    private static final Logger log = Logger.getLogger(ClienteJarvis.class.getName());

    /** O servidor segura o GET por até 25 s; 40 s é folga para a resposta chegar. */
    private static final Duration TIMEOUT_BUSCA = Duration.ofSeconds(40);
    private static final Duration TIMEOUT_RESULTADO = Duration.ofSeconds(30);
    /** Tentativas de postar o resultado: perder um print por soluço de rede seria uma pena. */
    private static final int TENTATIVAS_RESULTADO = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Configuracao config;
    /** O X-Pc-Ia de cada consulta; nunca lança (ver {@link EstadoDaIa#cabecalho}). */
    private final java.util.function.Supplier<String> cabecalhoIa;

    ClienteJarvis(Configuracao config) {
        this(config, () -> "ativo=0");
    }

    ClienteJarvis(Configuracao config, java.util.function.Supplier<String> cabecalhoIa) {
        this.config = config;
        this.cabecalhoIa = cabecalhoIa;
    }

    /**
     * O teto de {@code dados} no resultado: 32 KB para as consultas, como sempre, e
     * {@code inferencia.maxKib} só para a inferência, que devolve texto de modelo e tool_calls.
     */
    static int limiteDados(DiarioE2.Registro r, Configuracao config) {
        return "inferir".equals(r.acao()) ? config.ia().maxBytes() : 32_000;
    }

    void anunciarRotinas(CatalogoRotinas catalogo) throws IOException, InterruptedException {
        var lista = json.createArrayNode();
        for (Rotina rotina : catalogo.todas()) {
            var n = lista.addObject().put("nome", rotina.nome()).put("descricao", rotina.descricao());
            var capacidades = n.putArray("capacidades");
            rotina.capacidades().forEach(capacidades::add);
        }
        HttpRequest pedido = autenticar(HttpRequest.newBuilder(uri("/api/pc/v2/catalogo")))
                .timeout(TIMEOUT_RESULTADO).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(lista))).build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 204) throw new IOException("catálogo E2 HTTP " + resposta.statusCode());
    }

    EntregaE2 buscarComandoE2() throws IOException, InterruptedException {
        HttpRequest pedido = autenticar(HttpRequest.newBuilder(uri("/api/pc/v2/comandos")))
                .timeout(TIMEOUT_BUSCA).header("X-Pc-Apps", config.cabecalhoDeApps())
                .header("X-Pc-Ia", cabecalhoIa.get()).GET().build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() == 204) return null;
        if (resposta.statusCode() != 200) throw new IOException("busca E2 HTTP " + resposta.statusCode());
        JsonNode raiz = json.readTree(resposta.body());
        if (raiz.path("versao").asInt() != 2) throw new IOException("versão E2 incompatível");
        JsonNode c = raiz.path("comando");
        String id = texto(c, "id"), acao = texto(c, "acao");
        if (!id.matches("[0-9a-fA-F-]{36}") || acao.isBlank()) throw new IOException("comando E2 inválido");
        return new EntregaE2(new ComandoRecebido(id, acao, texto(c, "argumento"), c.path("dados")),
                Instant.parse(texto(raiz, "prazoInicio")), Instant.parse(texto(raiz, "prazoExecucao")));
    }

    boolean marcarE2(String id, String fase) throws IOException, InterruptedException {
        HttpRequest pedido = autenticar(HttpRequest.newBuilder(uri("/api/pc/v2/comandos/" + id + "/" + fase)))
                .timeout(TIMEOUT_RESULTADO).POST(HttpRequest.BodyPublishers.noBody()).build();
        return http.send(pedido, HttpResponse.BodyHandlers.discarding()).statusCode() == 204;
    }

    boolean cancelamentoE2(String id) {
        try {
            HttpRequest pedido = autenticar(HttpRequest.newBuilder(uri("/api/pc/v2/comandos/" + id + "/cancelamento")))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
            return resposta.statusCode() == 200 && json.readTree(resposta.body()).path("cancelar").asBoolean();
        } catch (Exception e) { return false; }
    }

    boolean enviarResultadoE2(DiarioE2.Registro r) throws IOException, InterruptedException {
        boolean arquivo = r.arquivo() != null && !r.arquivo().isBlank()
                && r.estado().equals("concluído");
        boolean edicao = r.dados() != null && r.dados().path("editId").isTextual();
        boolean entregaFalhou = false;
        if (arquivo) try {
            entregaFalhou = !enviarArquivo(r.id(), java.nio.file.Path.of(r.arquivo()));
        } catch (IOException e) {
            if (!edicao) throw e;
            entregaFalhou = true;
        }
        if (entregaFalhou && !edicao) throw new IOException("upload E2 recusado para " + r.id());
        var corpo = json.createObjectNode().put("estado", r.estado()).put("mensagem",
                entregaFalhou ? "salvo, entrega falhou: " + r.mensagem() : r.mensagem());
        if (r.codigo() != null) corpo.put("codigoSaida", r.codigo());
        corpo.put("arquivo", arquivo && !entregaFalhou);
        if (r.imagemBase64() != null && !r.imagemBase64().isBlank())
            corpo.put("imagemBase64", r.imagemBase64());
        if (r.dados() != null && !r.dados().isMissingNode()) {
            if (r.dados().toString().getBytes(StandardCharsets.UTF_8).length > limiteDados(r, config))
                throw new IOException("dados E2 acima do limite para " + r.id());
            corpo.set("dados", r.dados());
        }
        HttpRequest pedido = autenticar(HttpRequest.newBuilder(uri("/api/pc/v2/comandos/" + r.id() + "/resultado")))
                .timeout(TIMEOUT_RESULTADO).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo))).build();
        int status = http.send(pedido, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (RecusaDefinitiva.definitivo(status))
            throw new RecusaDefinitiva("resultado E2 HTTP " + status, status);
        if (status != 204) throw new IOException("resultado E2 HTTP " + status);
        return true;
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
                .header("X-Pc-Ia", cabecalhoIa.get())
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
        return new ComandoRecebido(id, acao, texto(raiz, "argumento"), raiz.path("dados"));
    }

    /** Posta o resultado, tentando de novo quando a rede falha. */
    void enviarResultado(Resultado resultado) throws InterruptedException {
        boolean arquivoEnviado = false;
        if (resultado.arquivo() != null && resultado.ok()) {
            try { arquivoEnviado = enviarArquivo(resultado); }
            catch (IOException e) { log.warning("upload do arquivo falhou: " + e.getMessage()); }
        }
        ObjectNode corpo = json.createObjectNode();
        corpo.put("comandoId", resultado.comandoId());
        corpo.put("ok", resultado.ok() && (resultado.arquivo() == null || arquivoEnviado));
        corpo.put("mensagem", resultado.arquivo() != null && !arquivoEnviado
                ? "arquivo gerado, mas upload falhou" : resultado.mensagem());
        corpo.put("arquivo", arquivoEnviado);
        if (resultado.dados() != null) {
            if (resultado.dados().toString().getBytes(StandardCharsets.UTF_8).length > 32_000) {
                corpo.put("ok", false);
                corpo.put("mensagem", "resultado estruturado acima do limite");
            } else corpo.set("dados", resultado.dados());
        }
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
                log.warning("a VPS recusou o resultado com HTTP " + resposta.statusCode());
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

    private boolean enviarArquivo(Resultado resultado) throws IOException, InterruptedException {
        return enviarArquivo(resultado.comandoId(), resultado.arquivo());
    }

    private boolean enviarArquivo(String id, java.nio.file.Path arquivo)
            throws IOException, InterruptedException {
        long tamanho = java.nio.file.Files.size(arquivo);
        if (tamanho == 0 || tamanho > 8L * 1024 * 1024) throw new IOException("arquivo vazio ou acima de 8 MiB");
        HttpRequest pedido = autenticar(HttpRequest.newBuilder(uri("/api/pc/arquivos/" + id)))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(arquivo)).build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        return resposta.statusCode() == 204;
    }

    /**
     * Pede à VPS que a tela aberta diga uma frase.
     *
     * <p>É a <b>única</b> chamada em que o agente fala sem ter recebido um comando antes: o
     * gatilho disparou e a HUD já estava na frente do usuário, então em vez de abrir outra janela
     * o Jarvis só responde que já está ali.
     *
     * <p>Vai a <b>chave</b> de uma frase, nunca o texto. O que a tela diz é decidido na VPS: este
     * arquivo de configuração mora numa máquina de mesa, e um pcToken vazado não pode virar
     * microfone aberto para a sala.
     *
     * <p>Não lança e não tenta de novo: é um agrado, não um comando. Falhou, o usuário vê a
     * janela vindo para a frente do mesmo jeito.
     */
    void pedirFala(String chave) {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("frase", chave);
        try {
            HttpRequest requisicao = autenticar(HttpRequest.newBuilder(uri("/api/pc/falar")))
                    .timeout(TIMEOUT_RESULTADO)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                    .build();
            HttpResponse<String> resposta =
                    http.send(requisicao, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() == 409) {
                // A VPS não vê HUD conectada. Acontece: a janela está aberta mas o SSE caiu.
                log.info("pedi a fala '" + chave + "', mas a VPS não vê tela conectada");
            } else if (resposta.statusCode() != 204) {
                log.warning("a VPS recusou a fala '" + chave + "' com HTTP "
                        + resposta.statusCode());
            }
        } catch (IOException e) {
            log.warning("não consegui pedir a fala '" + chave + "': " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
