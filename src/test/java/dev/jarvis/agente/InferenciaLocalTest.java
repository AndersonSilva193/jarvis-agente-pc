package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * A ação inferir contra um Ollama falso: o que vai, o que volta e cada código de erro que a VPS
 * usa para decidir o fallback.
 */
class InferenciaLocalTest {

    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";

    private OllamaFalso ollama;

    @BeforeEach void subir() throws Exception {
        ollama = new OllamaFalso();
    }

    @AfterEach void descer() {
        ollama.close();
    }

    private static Configuracao.ConfigIa config(URI url, int timeoutSegundos, int maxKib) {
        return new Configuracao.ConfigIa(true, url, "qwen3.5:9b", 16384, "20m", timeoutSegundos, maxKib);
    }

    private Resultado inferir(Configuracao.ConfigIa config, String dados) throws Exception {
        return new InferenciaLocal(config).inferir(
                new ComandoRecebido(ID, "inferir", "", OllamaFalso.JSON.readTree(dados)));
    }

    private Resultado inferir(String dados) throws Exception {
        return inferir(config(ollama.url(), 90, 256), dados);
    }

    private static String codigo(Resultado resultado) {
        return resultado.dados().path("codigoErro").asString("");
    }

    @Test
    @DisplayName("inferência normal: modelo, contexto, keep_alive e stream são os daqui")
    void inferenciaNormal() throws Exception {
        Resultado resultado = inferir("""
                {"model":"modelo-da-vps","stream":true,"keep_alive":"-1","format":"json",
                 "options":{"num_ctx":999999,"temperature":2},"think":false,
                 "messages":[{"role":"system","content":"seja breve"},
                             {"role":"user","content":"oi","images":["AAAA"],"extra":1}]}""");

        assertTrue(resultado.ok(), resultado.mensagem());
        assertEquals("olá", resultado.dados().path("message").path("content").asString(""));
        assertEquals("stop", resultado.dados().path("done_reason").asString(""));
        assertEquals(5, resultado.dados().path("eval_count").asInt());

        JsonNode enviado = ollama.pedidos.get(0);
        assertEquals("qwen3.5:9b", enviado.path("model").asString(""));
        assertFalse(enviado.path("stream").asBoolean(true));
        assertEquals("20m", enviado.path("keep_alive").asString(""));
        assertEquals(OllamaFalso.JSON.readTree("{\"num_ctx\":16384}"), enviado.path("options"),
                "nada das options da VPS passa, nem o temperature");
        assertTrue(enviado.path("format").isMissingNode());
        assertFalse(enviado.path("think").asBoolean(true));
        JsonNode usuario = enviado.path("messages").get(1);
        assertEquals("oi", usuario.path("content").asString(""));
        assertTrue(usuario.path("images").isMissingNode() && usuario.path("extra").isMissingNode());
    }

    @Test
    @DisplayName("tools e tool_calls no formato atual do Ollama, nos dois sentidos")
    void toolCalls() throws Exception {
        ollama.chat = (troca, corpo) -> OllamaFalso.json(troca, 200, """
                {"model":"qwen3.5:9b","message":{"role":"assistant","content":"",
                 "tool_calls":[{"function":{"index":0,"name":"abrir_app","arguments":{"nome":"spotify"}}}]},
                 "done":true,"done_reason":"stop"}""");

        Resultado resultado = inferir("""
                {"tools":[{"type":"function","function":{"name":"abrir_app","description":"Abre um app",
                   "parameters":{"type":"object","properties":{"nome":{"type":"string"}},"required":["nome"]}}}],
                 "messages":[{"role":"user","content":"abre o vscode e depois o spotify"},
                   {"role":"assistant","content":"","thinking":"preciso abrir",
                    "tool_calls":[{"type":"function","function":{"index":0,"name":"abrir_app","arguments":{"nome":"vscode"}}}]},
                   {"role":"tool","tool_name":"abrir_app","content":"abri o vscode"}]}""");

        assertTrue(resultado.ok(), resultado.mensagem());
        JsonNode chamada = resultado.dados().path("message").path("tool_calls").get(0).path("function");
        assertEquals("abrir_app", chamada.path("name").asString(""));
        assertEquals("spotify", chamada.path("arguments").path("nome").asString(""));

        JsonNode enviado = ollama.pedidos.get(0);
        JsonNode ferramenta = enviado.path("tools").get(0);
        assertEquals("function", ferramenta.path("type").asString(""));
        assertEquals("abrir_app", ferramenta.path("function").path("name").asString(""));
        assertEquals("object", ferramenta.path("function").path("parameters").path("type").asString(""));
        JsonNode assistente = enviado.path("messages").get(1);
        assertEquals("preciso abrir", assistente.path("thinking").asString(""));
        assertEquals("vscode", assistente.path("tool_calls").get(0).path("function")
                .path("arguments").path("nome").asString(""));
        assertEquals("abrir_app", enviado.path("messages").get(2).path("tool_name").asString(""));
    }

    @Test void ollamaForaDoAr() throws Exception {
        Resultado resultado = inferir(config(OllamaFalso.portaMorta(), 90, 256),
                "{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}");
        assertFalse(resultado.ok());
        assertEquals(InferenciaLocal.OLLAMA_OFFLINE, codigo(resultado));
    }

    @Test void timeout() throws Exception {
        ollama.chat = (troca, corpo) -> {
            Thread.sleep(3000);
            OllamaFalso.json(troca, 200, "{}");
        };
        long inicio = System.nanoTime();
        Resultado resultado = inferir(config(ollama.url(), 1, 256),
                "{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}");
        assertEquals(InferenciaLocal.TIMEOUT, codigo(resultado));
        assertTrue((System.nanoTime() - inicio) / 1_000_000 < 2900, "não esperou o servidor inteiro");
    }

    @Test void modeloAusente() throws Exception {
        ollama.chat = (troca, corpo) -> OllamaFalso.json(troca, 404,
                "{\"error\":\"model \\\"qwen3.5:9b\\\" not found, try pulling it first\"}");
        assertEquals(InferenciaLocal.MODELO_AUSENTE,
                codigo(inferir("{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}")));
    }

    @Test void semMemoria() throws Exception {
        ollama.chat = (troca, corpo) -> OllamaFalso.json(troca, 500,
                "{\"error\":\"model requires more system memory (12.3 GiB) than is available (8.0 GiB)\"}");
        assertEquals(InferenciaLocal.SEM_MEMORIA,
                codigo(inferir("{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}")));
    }

    @Test
    @DisplayName("pedido acima de inferencia.maxKib nem chega ao Ollama")
    void pedidoGrande() throws Exception {
        String grande = "x".repeat(2000);
        Resultado resultado = inferir(config(ollama.url(), 90, 1),
                "{\"messages\":[{\"role\":\"user\",\"content\":\"" + grande + "\"}]}");
        assertEquals(InferenciaLocal.PEDIDO_GRANDE, codigo(resultado));
        assertTrue(ollama.pedidos.isEmpty());
    }

    @Test void respostaGrande() throws Exception {
        String grande = "y".repeat(4000);
        ollama.chat = (troca, corpo) -> OllamaFalso.json(troca, 200,
                "{\"message\":{\"role\":\"assistant\",\"content\":\"" + grande + "\"},\"done\":true}");
        Resultado resultado = inferir(config(ollama.url(), 90, 1),
                "{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}");
        assertEquals(InferenciaLocal.RESPOSTA_GRANDE, codigo(resultado));
    }

    @Test
    @DisplayName("estrutura torta é recusada antes de ir ao Ollama")
    void pedidoInvalido() throws Exception {
        for (String dados : new String[] {
                "{}",
                "{\"messages\":[{\"role\":\"root\",\"content\":\"oi\"}]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":42}]}",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}],"
                        + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"abrir app; rm\",\"parameters\":{}}}]}",
                "{\"messages\":[{\"role\":\"assistant\",\"content\":\"\","
                        + "\"tool_calls\":[{\"function\":{\"name\":\"abrir_app\",\"arguments\":\"{}\"}}]}]}"}) {
            assertEquals(InferenciaLocal.PEDIDO_INVALIDO, codigo(inferir(dados)), dados);
        }
        assertTrue(ollama.pedidos.isEmpty());
    }

    @Test void desligada() throws Exception {
        var desligada = new Configuracao.ConfigIa(false, ollama.url(), "qwen3.5:9b", 16384, "20m", 90, 256);
        assertEquals(InferenciaLocal.IA_DESATIVADA,
                codigo(inferir(desligada, "{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}")));
        assertTrue(ollama.pedidos.isEmpty());
    }
}
