package dev.jarvis.agente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
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
 * A ação {@code inferir}: um {@code /api/chat} no Ollama deste PC.
 *
 * <p>Sem stream por padrão. Com {@code "stream": true} no pedido <b>e</b> um canal de parciais
 * ({@link Parcial}), o Ollama transmite e o texto vai à VPS em pedaços enquanto o modelo escreve —
 * é o que o holograma usa para montar peça por peça. O resultado final continua sendo o mesmo
 * {@code resumir} de sempre, com o texto inteiro: a VPS reconcilia por ele o que uma parcial
 * perdida deixou para trás.
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
    static final String CANCELADO = "cancelado";

    /** Teto de uma geração com stream, por cima da inatividade: a VPS desiste bem antes disso. */
    static final Duration TETO_DO_STREAM = Duration.ofMinutes(5);
    /** De quanto em quanto o texto pendente vai à VPS, mesmo sem quebra de linha. */
    static final Duration INTERVALO_DE_PARCIAL = Duration.ofMillis(300);

    /** Para onde vai cada pedaço do texto. Falso = a VPS desistiu (410), e o stream é largado. */
    @FunctionalInterface
    interface Parcial {
        boolean enviar(String id, int inicio, String texto);
    }

    private static final Set<String> PAPEIS = Set.of("system", "user", "assistant", "tool");
    private static final String NOME_DE_FERRAMENTA = "[A-Za-z0-9_-]{1,64}";
    /** Folga sobre o teto do resultado: a resposta crua traz métricas que não voltam à VPS. */
    private static final int FOLGA_RESPOSTA = 64 * 1024;

    private final Configuracao.ConfigIa config;
    /** Nulo = sem canal de parciais, e o {@code stream} do pedido é ignorado. */
    private final Parcial parcial;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    InferenciaLocal(Configuracao.ConfigIa config) {
        this(config, null);
    }

    InferenciaLocal(Configuracao.ConfigIa config, Parcial parcial) {
        this.config = config;
        this.parcial = parcial;
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
            ObjectNode corpo = montarCorpo(comando.dados());
            JsonNode resposta = corpo.path("stream").asBoolean(false)
                    ? transmitir(comando.id(), corpo) : chamar(corpo);
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

        // O stream só vale com quem receba as parciais; sem isso, o pedido é o de sempre.
        corpo.put("stream", parcial != null && pedido.path("stream").asBoolean(false));
        ObjectNode opcoes = corpo.putObject("options").put("num_ctx", config.contexto());
        // A temperatura é o único ajuste de amostragem que a VPS pode pedir, e presa em 0..1:
        // as options dela (num_ctx, temperature crua) continuam sem passar.
        JsonNode temperatura = pedido.path("temperatura");
        if (temperatura.isNumber() && Double.isFinite(temperatura.asDouble())) {
            opcoes.put("temperature", Math.max(0, Math.min(1, temperatura.asDouble())));
        }
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
        } catch (IOException e) {
            throw recusaDeRede(e);
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
            throw recusaDoOllama(resposta.statusCode(), raiz);
        }
        if (raiz == null || !raiz.path("message").isObject()) {
            throw new Recusa(FALHA_OLLAMA, "resposta do Ollama ilegível");
        }
        return raiz;
    }

    /** A falha de rede ao falar com o Ollama, com o código que a VPS entende. */
    private Recusa recusaDeRede(IOException e) {
        // O de conexão antes do HttpTimeoutException: é subclasse dele, e aqui é "fora do ar". O
        // HttpClient também embrulha a conexão recusada em IOException com o ConnectException dentro.
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException
                || e.getCause() instanceof ConnectException) {
            return new Recusa(OLLAMA_OFFLINE, "o Ollama não está no ar neste PC");
        }
        if (e instanceof HttpTimeoutException) {
            return new Recusa(TIMEOUT, "o Ollama não respondeu em " + config.timeoutSegundos() + " s");
        }
        log.warning("falha falando com o Ollama: " + e.getClass().getSimpleName());
        return new Recusa(FALHA_OLLAMA, "falha falando com o Ollama");
    }

    /** O HTTP fora do 200, ou o erro no meio do stream, com o código que a VPS entende. */
    private Recusa recusaDoOllama(int status, JsonNode raiz) {
        String erro = raiz != null && raiz.path("error").isString()
                ? raiz.path("error").stringValue().toLowerCase(Locale.ROOT) : "";
        log.warning("o Ollama respondeu HTTP " + status);
        if (erro.contains("memory") || erro.contains("oom")) {
            return new Recusa(SEM_MEMORIA, "memória insuficiente para o modelo");
        }
        if (status == 404 || erro.contains("not found")) {
            return new Recusa(MODELO_AUSENTE, "o modelo " + config.modelo() + " não está instalado");
        }
        return new Recusa(FALHA_OLLAMA, "o Ollama recusou o pedido (HTTP " + status + ")");
    }

    /**
     * O {@code /api/chat} com {@code stream: true}: lê o NDJSON do Ollama linha a linha, junta o
     * {@code message.content} e manda o texto à VPS a cada quebra de linha ou a cada
     * {@link #INTERVALO_DE_PARCIAL}. O {@code thinking} não vai — não é texto da resposta.
     *
     * <p>Três coisas largam o stream, e fechar a resposta é o que faz o Ollama parar de gerar e
     * soltar a GPU: a VPS respondendo 410 a uma parcial ({@link #CANCELADO}), nada chegando em
     * {@code ollama.timeoutSegundos} ({@link #TIMEOUT}, aqui como <b>inatividade</b>: uma geração
     * longa que está escrevendo não é um Ollama travado) e o {@link #TETO_DO_STREAM}.
     *
     * <p>Volta a mesma forma da resposta sem stream, para o {@code resumir} não saber a diferença.
     */
    private JsonNode transmitir(String id, ObjectNode corpo) throws Recusa, InterruptedException {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(config.url() + "/api/chat"))
                // Com stream o limite cobre só a chegada dos cabeçalhos; dali em diante vale a vigia.
                .timeout(Duration.ofSeconds(config.timeoutSegundos()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(corpo)))
                .build();
        HttpResponse<InputStream> resposta;
        try {
            resposta = http.send(pedido, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw recusaDeRede(e);
        }
        if (resposta.statusCode() != 200) {
            JsonNode raiz;
            try (InputStream entrada = resposta.body()) {
                raiz = json.readTree(entrada.readNBytes(FOLGA_RESPOSTA));
            } catch (IOException | JacksonException e) {
                raiz = null;
            }
            throw recusaDoOllama(resposta.statusCode(), raiz);
        }

        InputStream entrada = resposta.body();
        Vigia vigia = new Vigia(entrada, Duration.ofSeconds(config.timeoutSegundos()));
        StringBuilder texto = new StringBuilder();
        StringBuilder pendente = new StringBuilder();
        int enviado = 0;
        long bytes = 0;
        long ultimoEnvio = System.nanoTime();
        JsonNode ultimo = null;
        try (BufferedReader linhas = new BufferedReader(new InputStreamReader(entrada, StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = linhas.readLine()) != null) {
                vigia.sinal();
                if (linha.isBlank()) continue;
                JsonNode pedaco;
                try {
                    pedaco = json.readTree(linha);
                } catch (JacksonException e) {
                    throw new Recusa(FALHA_OLLAMA, "stream do Ollama ilegível");
                }
                if (pedaco.path("error").isString()) {
                    throw recusaDoOllama(200, pedaco);
                }
                String conteudo = pedaco.path("message").path("content").asString("");
                bytes += conteudo.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > config.maxBytes()) {
                    throw new Recusa(RESPOSTA_GRANDE, "a resposta passou de " + config.maxKib() + " KiB");
                }
                texto.append(conteudo);
                pendente.append(conteudo);
                boolean fim = pedaco.path("done").asBoolean(false);
                if (fim) ultimo = pedaco;
                if (!pendente.isEmpty() && (fim || pendente.indexOf("\n") >= 0
                        || System.nanoTime() - ultimoEnvio >= INTERVALO_DE_PARCIAL.toNanos())) {
                    if (!parcial.enviar(id, enviado, pendente.toString())) {
                        vigia.largar(CANCELADO);
                        throw new Recusa(CANCELADO, "a VPS desistiu da inferência");
                    }
                    enviado += pendente.length();
                    pendente.setLength(0);
                    ultimoEnvio = System.nanoTime();
                }
                if (fim) break;
            }
        } catch (IOException e) {
            String motivo = vigia.motivo();
            if (TIMEOUT.equals(motivo)) {
                throw new Recusa(TIMEOUT, "o Ollama ficou " + config.timeoutSegundos() + " s sem escrever");
            }
            if (motivo != null) {
                throw new Recusa(motivo, "o stream do Ollama foi largado");
            }
            throw recusaDeRede(e);
        } finally {
            vigia.parar();
        }
        if (ultimo == null) {
            throw new Recusa(FALHA_OLLAMA, "o stream do Ollama acabou sem o fim");
        }
        ObjectNode raiz = ((ObjectNode) ultimo).deepCopy();
        ObjectNode mensagem = raiz.putObject("message").put("role", "assistant").put("content", texto.toString());
        JsonNode chamadas = ultimo.path("message").path("tool_calls");
        if (chamadas.isArray()) mensagem.set("tool_calls", chamadas.deepCopy());
        return raiz;
    }

    /**
     * Fecha o stream de dentro quando ele para de andar: sem linha nova na inatividade, ou depois
     * do {@link #TETO_DO_STREAM}. Fechar a resposta faz a leitura bloqueada sair com erro, e o
     * {@link #motivo()} diz por quê.
     */
    private static final class Vigia {
        private final InputStream entrada;
        private final long inicio = System.nanoTime();
        private final Thread thread;
        private volatile long ultimoSinal = System.nanoTime();
        private volatile String motivo;

        Vigia(InputStream entrada, Duration inatividade) {
            this.entrada = entrada;
            this.thread = Thread.ofVirtual().name("vigia-inferencia").start(() -> {
                try {
                    while (motivo == null) {
                        Thread.sleep(100);
                        long agora = System.nanoTime();
                        if (agora - ultimoSinal > inatividade.toNanos()
                                || agora - inicio > TETO_DO_STREAM.toNanos()) {
                            largar(TIMEOUT);
                        }
                    }
                } catch (InterruptedException e) {
                    // Parada normal: o stream terminou.
                }
            });
        }

        void sinal() {
            ultimoSinal = System.nanoTime();
        }

        synchronized void largar(String porque) {
            if (motivo != null) return;
            motivo = porque;
            try {
                entrada.close();
            } catch (IOException e) {
                // Já fechado: o efeito é o mesmo.
            }
        }

        String motivo() {
            return motivo;
        }

        void parar() {
            thread.interrupt();
        }
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
