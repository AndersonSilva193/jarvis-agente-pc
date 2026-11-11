package dev.jarvis.agente;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Acompanha as tarefas passadas à empresa, cada uma na sua thread.
 *
 * <p>Thread própria porque uma tarefa leva minutos, e o laço principal do agente não pode parar de
 * buscar comandos nesse meio tempo. Quem conclui e entrega é o {@code concluir} recebido.
 */
final class ExecutorDaEmpresa implements AutoCloseable {

    static final Duration INTERVALO = Duration.ofSeconds(5);
    static final Duration PRAZO = Duration.ofMinutes(55);

    private static final Logger log = Logger.getLogger(ExecutorDaEmpresa.class.getName());
    private static final int MAX_EM_VOO = 3;
    private static final int MAX_PEDIDO = 8000;
    private static final int MAX_RESUMO = 900;
    private static final int MAX_MENSAGEM = 1000;
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<String> emVoo = new HashSet<>();
    private final ExecutorService threads = Executors.newCachedThreadPool(tarefa -> {
        Thread thread = new Thread(tarefa, "empresa");
        thread.setDaemon(true);
        return thread;
    });
    private final ClienteEmpresa cliente;
    private final Path pastaArquivos;
    private final Duration intervalo;
    private final Duration prazo;

    ExecutorDaEmpresa(ClienteEmpresa cliente, Path pastaArquivos, Duration intervalo, Duration prazo) {
        this.cliente = cliente;
        this.pastaArquivos = pastaArquivos;
        this.intervalo = intervalo;
        this.prazo = prazo;
    }

    synchronized boolean emVoo(String id) {
        return emVoo.contains(id);
    }

    /** @return falso quando já há tarefas demais; aí ninguém vai chamar o {@code concluir} */
    synchronized boolean aceitar(ComandoRecebido comando, BooleanSupplier cancelado,
            BiConsumer<String, Resultado> concluir) {
        if (emVoo.size() >= MAX_EM_VOO) return false;
        emVoo.add(comando.id());
        threads.execute(() -> rodar(comando, cancelado, concluir));
        return true;
    }

    private void rodar(ComandoRecebido comando, BooleanSupplier cancelado, BiConsumer<String, Resultado> concluir) {
        try {
            Fim fim = acompanhar(comando, cancelado);
            concluir.accept(fim.estado(), fim.resultado());
        } catch (InterruptedException encerrando) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                emVoo.remove(comando.id());
            }
        }
    }

    private record Fim(String estado, Resultado resultado) { }

    private static Fim falha(ComandoRecebido comando, String motivo) {
        return new Fim("falhou", Resultado.falhou(comando, cortar(motivo, MAX_MENSAGEM)));
    }

    private Fim acompanhar(ComandoRecebido comando, BooleanSupplier cancelado) throws InterruptedException {
        String pedido = comando.dados() == null ? "" : comando.dados().path("pedido").asString("").trim();
        if (pedido.isEmpty() || pedido.length() > MAX_PEDIDO) {
            return falha(comando, "pedido inválido para a empresa");
        }
        String idDaExecucao;
        try {
            idDaExecucao = cliente.delegar(comando.id(), pedido);
        } catch (IOException foraDoAr) {
            return falha(comando, AcoesDaEmpresa.SEM_RESPOSTA);
        }
        long limite = System.nanoTime() + prazo.toNanos();
        while (System.nanoTime() < limite) {
            if (cancelado.getAsBoolean()) {
                return new Fim("cancelado", Resultado.falhou(comando, "cancelado pelo dono"));
            }
            try {
                ClienteEmpresa.Execucao execucao = cliente.execucao(idDaExecucao);
                switch (execucao.estado()) {
                    case "done":
                        return new Fim("concluído", concluida(comando, execucao.texto()));
                    case "needs_attention":
                        return falha(comando, execucao.motivo());
                    case ClienteEmpresa.DESCONHECIDA:
                        return falha(comando, "O Hermes3D reiniciou durante a tarefa");
                    default:
                        break;
                }
            } catch (IOException instavel) {
                // O supervisor pode estar religando o adaptador: quem decide é o prazo.
                log.fine("Hermes3D sem resposta na consulta: " + instavel.getMessage());
            }
            Thread.sleep(intervalo.toMillis());
        }
        return falha(comando, "A tarefa passou do prazo e continua sem resposta do Hermes3D");
    }

    private Resultado concluida(ComandoRecebido comando, String texto) {
        ObjectNode dados = json.createObjectNode().put("resumo", cortar(texto, MAX_RESUMO));
        if (texto.length() <= MAX_RESUMO) {
            return Resultado.comDados(comando, dados, "tarefa concluída");
        }
        try {
            Path arquivo = salvar(texto);
            return new Resultado(comando.id(), true, arquivo.getFileName().toString(), null, arquivo, dados);
        } catch (IOException falha) {
            log.warning("não consegui salvar o resultado da empresa: " + falha.getMessage());
            return Resultado.comDados(comando, dados, "resultado completo não pôde ser salvo");
        }
    }

    private Path salvar(String texto) throws IOException {
        Files.createDirectories(pastaArquivos);
        String carimbo = LocalDateTime.now().format(CARIMBO);
        for (int tentativa = 1; ; tentativa++) {
            String sufixo = tentativa == 1 ? "" : "-" + tentativa;
            Path arquivo = pastaArquivos.resolve("empresa-" + carimbo + sufixo + ".md");
            try {
                return Files.writeString(arquivo, texto, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ocupado) {
                log.fine("nome já usado: " + arquivo.getFileName());
            }
        }
    }

    private static String cortar(String texto, int limite) {
        if (texto.length() <= limite) return texto;
        int fim = limite - 3;
        if (Character.isHighSurrogate(texto.charAt(fim - 1))) fim--;
        return texto.substring(0, fim) + "...";
    }

    @Override
    public void close() {
        threads.shutdownNow();
    }
}
