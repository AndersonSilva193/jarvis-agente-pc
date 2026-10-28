package dev.jarvis.agente;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A ação {@code inferir}: um {@code /api/chat} no Ollama deste PC, sem stream.
 *
 * <p>O corpo que a VPS manda é só matéria-prima. O que vai ao Ollama é montado aqui do zero, com
 * uma lista fechada de campos: mensagens, ferramentas e o {@code think}. Modelo, contexto e
 * keep_alive vêm do {@code config.properties} — a VPS não escolhe qual modelo ocupa a GPU nem por
 * quanto tempo, e um {@code num_ctx} gigante vindo de lá não derruba a placa daqui.
 *
 * <p>Toda falha volta com um {@code dados.codigoErro} estável, que é o que a VPS usa para decidir
 * o fallback. A mensagem é curta e escrita aqui; o texto do Ollama não sai.
 */
final class InferenciaLocal {

    private static final Logger log = Logger.getLogger(InferenciaLocal.class.getName());

    static final String IA_DESATIVADA = "ia_desativada";
    static final String PEDIDO_GRANDE = "pedido_grande";
    static final String PEDIDO_INVALIDO = "pedido_invalido";
    static final String OLLAMA_OFFLINE = "ollama_offline";
    static final String MODELO_AUSENTE = "modelo_ausente";
    static final String SEM_MEMORIA = "sem_memoria";
    static final String TIMEOUT = "timeout";
    static final String RESPOSTA_GRANDE = "resposta_grande";
    static final String FALHA_OLLAMA = "falha_ollama";
    static final String FILA_CHEIA = "fila_cheia";

    private static final Set<String> PAPEIS = Set.of("system", "user", "assistant", "tool");
    private static final String NOME_DE_FERRAMENTA = "[A-Za-z0-9_-]{1,64}";
    /** Folga sobre o teto do resultado: a resposta crua traz métricas que não voltam à VPS. */
    private static final int FOLGA_RESPOSTA = 64 * 1024;

    private final Configuracao.ConfigIa config;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    InferenciaLocal(Configuracao.ConfigIa config) {
        this.config = config;
    }

    /** Uma recusa com código: o que a VPS lê para decidir o fallback. */
    private static final class Recusa extends Exception {
        final String codigo;

        Recusa(String codigo, String mensagem) {
            super(mensagem, null, false, false);
            this.codigo = codigo;
        }
    }

    Resultado inferir(ComandoRecebido comando) throws InterruptedException {
        try {
            JsonNode resposta = chamar(montarCorpo(comando.dados()));
            ObjectNode dados = resumir(resposta);
            if (json.writeValueAsBytes(dados).length > config.maxBytes()) {
                throw new Recusa(RESPOSTA_GRANDE, "a resposta passou de " + config.maxKib() + " KiB");
            }
            return Resultado.comDados(comando, dados, "inferência concluída");
        } catch (Recusa recusa) {
            log.warning("inferir " + comando.id() + " recusada: " + recusa.codigo);
            return falha(comando, recusa.codigo, recusa.getMessage());
        }
    }

    static Resultado falha(ComandoRecebido comando, String codigo, String mensagem) {
        ObjectNode dados = JsonMapper.builder().build().createObjectNode().put("codigoErro", codigo);
        return new Resultado(comando.id(), false, mensagem, null, null, dados);
    }

    /** O corpo do /api/chat, montado do zero a partir do que a VPS mandou. */
    ObjectNode montarCorpo(JsonNode pedido) throws Recusa {
        if (!config.ativo()) {
            throw new Recusa(IA_DESATIVADA, "a IA local está desligada neste PC");
        }
        // Medido antes de tudo: nem a validação gasta tempo com pedido acima do teto.
        if (pedido != null && json.writeValueAsBytes(pedido).length > config.maxBytes()) {
            throw new Recusa(PEDIDO_GRANDE, "o pedido passou de " + config.maxKib() + " KiB");
        }
        if (pedido == null || !pedido.isObject()) {
            throw invalido("o pedido não é um objeto");
        }
        ObjectNode corpo = json.createObjectNode();
        corpo.put("model", config.modelo());

        JsonNode mensagens = pedido.path("messages");
        if (!mensagens.isArray() || mensagens.isEmpty()) throw invalido("messages vazio");
        ArrayNode saida = corpo.putArray("messages");
        for (JsonNode mensagem : mensagens) saida.add(mensagem(mensagem));

        JsonNode ferramentas = pedido.path("tools");
        if (!ferramentas.isMissingNode() && !ferramentas.isNull()) {
            if (!ferramentas.isArray()) throw invalido("tools não é uma lista");
            if (!ferramentas.isEmpty()) {
                ArrayNode lista = corpo.putArray("tools");
                for (JsonNode ferramenta : ferramentas) lista.add(ferramenta(ferramenta));
            }
        }
        JsonNode pensar = pedido.path("think");
        if (pensar.isBoolean()) corpo.put("think", pensar.asBoolean());

        corpo.put("stream", false);
        corpo.putObject("options").put("num_ctx", config.contexto());
        corpo.put("keep_alive", config.keepAlive());
        return corpo;
    }

    private ObjectNode mensagem(JsonNode m) throws Recusa {
        if (!m.isObject()) throw invalido("mensagem não é um objeto");
        String papel = m.path("role").isString() ? m.path("role").stringValue() : "";
        if (!PAPEIS.contains(papel)) throw invalido("role desconhecido");
        JsonNode conteudo = m.path("content");
        // O assistente que só chamou ferramenta pode vir sem content; o resto, nunca.
        boolean semConteudo = papel.equals("assistant") && (conteudo.isMissingNode() || conteudo.isNull());
        if (!semConteudo && !conteudo.isString()) throw invalido("content não é texto");
        ObjectNode saida = json.createObjectNode()
                .put("role", papel).put("content", semConteudo ? "" : conteudo.stringValue());
        if (papel.equals("assistant")) {
            JsonNode pensamento = m.path("thinking");
            if (pensamento.isString()) saida.put("thinking", pensamento.stringValue());
            JsonNode chamadas = m.path("tool_calls");
            if (!chamadas.isMissingNode() && !chamadas.isNull()) {
                if (!chamadas.isArray()) throw invalido("tool_calls não é uma lista");
                ArrayNode lista = saida.putArray("tool_calls");
                for (JsonNode chamada : chamadas) lista.add(chamada(chamada));
            }
        }
        if (papel.equals("tool")) {
            JsonNode nome = m.path("tool_name");
            if (!nome.isMissingNode()) {
                if (!nome.isString() || !nome.stringValue().matches(NOME_DE_FERRAMENTA))
                    throw invalido("tool_name inválido");
                saida.put("tool_name", nome.stringValue());
            }
        }
        return saida;
    }

    private ObjectNode chamada(JsonNode c) throws Recusa {
        JsonNode funcao = c.path("function");
        if (!funcao.isObject() || !funcao.path("name").isString()
                || !funcao.path("name").stringValue().matches(NOME_DE_FERRAMENTA)
                || !funcao.path("arguments").isObject()) {
            throw invalido("tool_call inválida");
        }
        ObjectNode saida = json.createObjectNode().put("type", "function");
        ObjectNode f = saida.putObject("function");
        if (funcao.path("index").isIntegralNumber()) f.put("index", funcao.path("index").asInt());
        f.put("name", funcao.path("name").stringValue());
        f.set("arguments", funcao.path("arguments").deepCopy());
        return saida;
    }

    private ObjectNode ferramenta(JsonNode t) throws Recusa {
        JsonNode funcao = t.path("function");
        if (!"function".equals(t.path("type").asString("")) || !funcao.isObject()
                || !funcao.path("name").isString()
                || !funcao.path("name").stringValue().matches(NOME_DE_FERRAMENTA)
                || !funcao.path("parameters").isObject()) {
            throw invalido("tool inválida");
        }
        ObjectNode saida = json.createObjectNode().put("type", "function");
        ObjectNode f = saida.putObject("function").put("name", funcao.path("name").stringValue());
        JsonNode descricao = funcao.path("description");
        if (descricao.isString()) f.put("description", descricao.stringValue());
        else if (!descricao.isMissingNode() && !descricao.isNull()) throw invalido("description não é texto");
        f.set("parameters", funcao.path("parameters").deepCopy());
        return saida;
    }

    private JsonNode chamar(ObjectNode corpo) throws Recusa, InterruptedException {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(config.url() + "/api/chat"))
                // Com stream=false os cabeçalhos só chegam no fim: o limite cobre a geração toda.
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(corpo)))
                .build();
        HttpResponse<InputStream> resposta;
        byte[] bytes;
        try {
            resposta = http.send(pedido, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream entrada = resposta.body()) {
                bytes = entrada.readNBytes(config.maxBytes() + FOLGA_RESPOSTA + 1);
            }
        } catch (HttpConnectTimeoutException | ConnectException e) {
            // Antes do HttpTimeoutException: o de conexão é subclasse dele, e aqui é "fora do ar".
            throw new Recusa(OLLAMA_OFFLINE, "o Ollama não está no ar neste PC");
        } catch (HttpTimeoutException e) {
            throw new Recusa(TIMEOUT, "o Ollama não respondeu em " + config.timeoutSegundos() + " s");
        } catch (IOException e) {
            // O HttpClient embrulha a conexão recusada em IOException com o ConnectException dentro.
            if (e.getCause() instanceof ConnectException) {
                throw new Recusa(OLLAMA_OFFLINE, "o Ollama não está no ar neste PC");
            }
            log.warning("falha falando com o Ollama: " + e.getClass().getSimpleName());
            throw new Recusa(FALHA_OLLAMA, "falha falando com o Ollama");
        }
        if (bytes.length > config.maxBytes() + FOLGA_RESPOSTA) {
            throw new Recusa(RESPOSTA_GRANDE, "a resposta passou de " + config.maxKib() + " KiB");
        }
        JsonNode raiz;
        try {
            raiz = json.readTree(bytes);
        } catch (JacksonException e) {
            raiz = null;
        }
        if (resposta.statusCode() != 200) {
            String erro = raiz != null && raiz.path("error").isString()
                    ? raiz.path("error").stringValue().toLowerCase(Locale.ROOT) : "";
            log.warning("o Ollama respondeu HTTP " + resposta.statusCode());
            if (erro.contains("memory") || erro.contains("oom")) {
                throw new Recusa(SEM_MEMORIA, "memória insuficiente para o modelo");
            }
            if (resposta.statusCode() == 404 || erro.contains("not found")) {
                throw new Recusa(MODELO_AUSENTE, "o modelo " + config.modelo() + " não está instalado");
            }
            throw new Recusa(FALHA_OLLAMA, "o Ollama recusou o pedido (HTTP " + resposta.statusCode() + ")");
        }
        if (raiz == null || !raiz.path("message").isObject()) {
            throw new Recusa(FALHA_OLLAMA, "resposta do Ollama ilegível");
        }
        return raiz;
    }

    /** Só o que a VPS precisa, reconstruído: nada do Ollama passa sem ser escolhido aqui. */
    private ObjectNode resumir(JsonNode raiz) throws Recusa {
        JsonNode m = raiz.path("message");
        ObjectNode dados = json.createObjectNode();
        ObjectNode mensagem = dados.putObject("message").put("role", "assistant")
                .put("content", m.path("content").asString(""));
        if (m.path("thinking").isString()) mensagem.put("thinking", m.path("thinking").stringValue());
        JsonNode chamadas = m.path("tool_calls");
        if (chamadas.isArray() && !chamadas.isEmpty()) {
            ArrayNode lista = mensagem.putArray("tool_calls");
            for (JsonNode chamada : chamadas) {
                JsonNode funcao = chamada.path("function");
                if (!funcao.path("name").isString()) {
                    throw new Recusa(FALHA_OLLAMA, "tool_call sem nome na resposta do Ollama");
                }
                ObjectNode f = lista.addObject().put("type", "function").putObject("function");
                if (funcao.path("index").isIntegralNumber()) f.put("index", funcao.path("index").asInt());
                f.put("name", funcao.path("name").stringValue());
                f.set("arguments", funcao.path("arguments").isObject()
                        ? funcao.path("arguments").deepCopy() : json.createObjectNode());
            }
        }
        dados.put("modelo", config.modelo());
        dados.put("done_reason", raiz.path("done_reason").asString(""));
        for (String metrica : new String[] {"prompt_eval_count", "eval_count", "total_duration"}) {
            if (raiz.path(metrica).isIntegralNumber()) dados.put(metrica, raiz.path(metrica).asLong());
        }
        return dados;
    }

    private static Recusa invalido(String motivo) {
        return new Recusa(PEDIDO_INVALIDO, "pedido de inferência inválido: " + motivo);
    }
}
