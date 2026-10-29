package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;

import tools.jackson.databind.JsonNode;

/**
 * O inferir com stream contra um Ollama falso que transmite como o de verdade: uma linha NDJSON
 * por pedaço ({@code message.content} com o token, {@code done:false}) e a última com
 * {@code done:true}, {@code done_reason} e as métricas.
 */
class InferenciaLocalStreamTest {

    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";

    private OllamaFalso ollama;
    private final List<String> parciais = new CopyOnWriteArrayList<>();

    @BeforeEach void subir() throws Exception {
        ollama = new OllamaFalso();
    }

    @AfterEach void descer() {
        ollama.close();
    }

    private Resultado inferir(int timeoutSegundos, InferenciaLocal.Parcial parcial, String dados) throws Exception {
        var config = new Configuracao.ConfigIa(true, ollama.url(), "qwen3.5:9b", 16384, "20m", timeoutSegundos, 256);
        return new InferenciaLocal(config, parcial).inferir(
                new ComandoRecebido(ID, "inferir", "", OllamaFalso.JSON.readTree(dados)));
    }

    private static final String PEDIDO = """
            {"stream":true,"temperatura":0.4,"think":false,
             "messages":[{"role":"system","content":"gere NDJSON"},{"role":"user","content":"Gere: cubo"}]}""";

    /** Escreve as linhas uma a uma, com pausa, numa resposta em chunks. */
    private static void transmitir(HttpExchange troca, List<String> linhas, long pausaMs) throws Exception {
        troca.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        troca.sendResponseHeaders(200, 0);
        try (OutputStream saida = troca.getResponseBody()) {
            for (String linha : linhas) {
                saida.write((linha + "\n").getBytes(StandardCharsets.UTF_8));
                saida.flush();
                if (pausaMs > 0) Thread.sleep(pausaMs);
            }
        }
    }

    private static String pedaco(String conteudo) {
        return "{\"model\":\"qwen3.5:9b\",\"message\":{\"role\":\"assistant\",\"content\":"
                + OllamaFalso.JSON.writeValueAsString(conteudo) + "},\"done\":false}";
    }

    private static final String FIM = "{\"model\":\"qwen3.5:9b\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
            + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":40,\"eval_count\":12,\"total_duration\":999}";

    @Test
    void mandaParcialNaQuebraDeLinhaEOResultadoLevaOTextoInteiro() throws Exception {
        ollama.chat = (troca, corpo) -> transmitir(troca, List.of(pedaco("{\"t\":"),
                pedaco("\"meta\"}\n{\"t\""), pedaco(":\"fim\"}"), FIM), 0);

        Resultado resultado = inferir(90, (id, inicio, texto) -> parciais.add(inicio + ":" + texto), PEDIDO);

        assertTrue(resultado.ok(), resultado.mensagem());
        assertEquals("{\"t\":\"meta\"}\n{\"t\":\"fim\"}",
                resultado.dados().path("message").path("content").asString(""));
        assertEquals("stop", resultado.dados().path("done_reason").asString(""));
        assertEquals(12, resultado.dados().path("eval_count").asInt());
        assertEquals(List.of("0:{\"t\":\"meta\"}\n{\"t\"", "17::\"fim\"}"), parciais);

        JsonNode enviado = ollama.pedidos.get(0);
        assertTrue(enviado.path("stream").asBoolean(false));
        assertEquals(0.4, enviado.path("options").path("temperature").asDouble());
        assertEquals(16384, enviado.path("options").path("num_ctx").asInt());
        assertFalse(enviado.path("think").asBoolean(true));
    }

    @Test
    void temperaturaForaDaFaixaEPresa() throws Exception {
        ollama.chat = (troca, corpo) -> transmitir(troca, List.of(pedaco("ok"), FIM), 0);

        inferir(90, (id, inicio, texto) -> true, PEDIDO.replace("0.4", "7"));

        assertEquals(1.0, ollama.pedidos.get(0).path("options").path("temperature").asDouble());
    }

    @Test
    void semCanalDeParciaisOStreamDoPedidoEIgnorado() throws Exception {
        Resultado resultado = inferir(90, null, PEDIDO);

        assertTrue(resultado.ok(), resultado.mensagem());
        assertFalse(ollama.pedidos.get(0).path("stream").asBoolean(true));
    }

    @Test
    void oQuatrocentosEDezDaVpsLargaOOllama() throws Exception {
        ollama.chat = (troca, corpo) -> transmitir(troca, List.of(pedaco("linha 1\n"), pedaco("linha 2\n"),
                pedaco("linha 3\n"), FIM), 20);

        Resultado resultado = inferir(90, (id, inicio, texto) -> parciais.add(texto) && false, PEDIDO);

        assertFalse(resultado.ok());
        assertEquals(InferenciaLocal.CANCELADO, resultado.dados().path("codigoErro").asString(""));
        assertEquals(List.of("linha 1\n"), parciais);
    }

    @Test
    void oOllamaCaladoPorMaisQueOTimeoutCaiPorInatividade() {
        ollama.chat = (troca, corpo) -> transmitir(troca, List.of(pedaco("{\"t\":\"meta\"}\n"), FIM), 4_000);

        Resultado resultado = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> inferir(1, (id, inicio, texto) -> parciais.add(texto), PEDIDO));

        assertFalse(resultado.ok());
        assertEquals(InferenciaLocal.TIMEOUT, resultado.dados().path("codigoErro").asString(""));
        assertEquals(List.of("{\"t\":\"meta\"}\n"), parciais, "o que chegou antes do silêncio foi entregue");
    }

    @Test
    void erroNoMeioDoStreamVoltaComCodigo() throws Exception {
        ollama.chat = (troca, corpo) -> transmitir(troca, List.of(pedaco("a"),
                "{\"error\":\"CUDA error: out of memory\"}"), 0);

        Resultado resultado = inferir(90, (id, inicio, texto) -> true, PEDIDO);

        assertFalse(resultado.ok());
        assertEquals(InferenciaLocal.SEM_MEMORIA, resultado.dados().path("codigoErro").asString(""));
    }
}
