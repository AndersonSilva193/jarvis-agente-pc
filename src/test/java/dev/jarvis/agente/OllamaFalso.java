package dev.jarvis.agente;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Um servidor HTTP em 127.0.0.1 fazendo o papel do Ollama: {@code /api/chat} e {@code /api/ps}.
 *
 * <p>Guarda os corpos recebidos (para conferir o que o agente impôs) e o pico de requisições
 * simultâneas no chat (para conferir a fila).
 */
final class OllamaFalso implements AutoCloseable {

    interface Resposta {
        void responder(HttpExchange troca, JsonNode corpo) throws Exception;
    }

    static final JsonMapper JSON = JsonMapper.builder().build();

    final List<JsonNode> pedidos = new CopyOnWriteArrayList<>();
    final AtomicInteger consultasPs = new AtomicInteger();
    final AtomicInteger pico = new AtomicInteger();
    private final AtomicInteger simultaneos = new AtomicInteger();
    private final HttpServer servidor;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    volatile Resposta chat = (troca, corpo) -> json(troca, 200, """
            {"model":"qwen3.5:9b","message":{"role":"assistant","content":"olá"},
             "done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":5,
             "total_duration":123456}""");
    volatile Resposta ps = (troca, corpo) -> json(troca, 200, """
            {"models":[{"name":"qwen3.5:9b","model":"qwen3.5:9b","size_vram":6000000000}]}""");

    OllamaFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.setExecutor(threads);
        servidor.createContext("/api/chat", troca -> {
            int agora = simultaneos.incrementAndGet();
            pico.accumulateAndGet(agora, Math::max);
            try {
                JsonNode corpo = JSON.readTree(troca.getRequestBody().readAllBytes());
                pedidos.add(corpo);
                chat.responder(troca, corpo);
            } catch (Exception e) {
                troca.close();
            } finally {
                simultaneos.decrementAndGet();
            }
        });
        servidor.createContext("/api/ps", troca -> {
            consultasPs.incrementAndGet();
            try {
                ps.responder(troca, null);
            } catch (Exception e) {
                troca.close();
            }
        });
        servidor.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
    }

    static void json(HttpExchange troca, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().set("Content-Type", "application/json");
        troca.sendResponseHeaders(status, bytes.length);
        try (OutputStream saida = troca.getResponseBody()) {
            saida.write(bytes);
        }
    }

    /**
     * Uma porta em que ninguém escuta: a de um socket que acabou de fechar. Com um HttpServer
     * nunca iniciado não serve — o stop dele não solta a porta, e a conexão fica aceita e muda.
     */
    static URI portaMorta() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    @Override
    public void close() {
        servidor.stop(0);
        threads.shutdownNow();
    }
}
