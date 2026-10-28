package dev.jarvis.agente;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O cabeçalho {@code X-Pc-Ia}: o que a VPS precisa saber da IA local antes de mandar um
 * {@code inferir}.
 *
 * <p>Vai em toda consulta de comandos, como o {@code X-Pc-Apps}, no formato {@code chave=valor}
 * separado por {@code ;}:
 *
 * <pre>ativo=1;ollama=1;modelo=qwen3.5:9b;carregado=1;gpu=1;vramLivreMb=9216;vramTotalMb=12288;gpuUso=35;ocupada=0;inferindo=0</pre>
 *
 * <p>Medir custa uma chamada ao {@code /api/ps} e um nvidia-smi, por isso a medida vale 15 s. Só o
 * {@code inferindo} é lido na hora.
 */
final class EstadoDaIa {

    /** O que a fila conta para o "ocupada": uso alto da GPU só é "ocupada" se não for nosso. */
    interface Atividade {
        boolean inferindo();

        Instant fimDaUltima();
    }

    /** Devolve a saída do nvidia-smi; lança quando ele não existe ou falha. */
    interface LeitorDeGpu {
        String ler() throws IOException, InterruptedException;
    }

    /** Argumentos fixos, como todo programa que o agente roda (ver {@link Processos}). */
    static final LeitorDeGpu NVIDIA_SMI = () -> {
        Processos.Saida saida = Processos.rodarComLimite(Duration.ofSeconds(3), "nvidia-smi",
                "--query-gpu=memory.used,memory.total,utilization.gpu",
                "--format=csv,noheader,nounits");
        if (!saida.ok()) throw new IOException("nvidia-smi saiu com " + saida.codigo());
        return saida.texto();
    };

    static final Duration CACHE = Duration.ofSeconds(15);
    /** Uso de GPU a partir do qual ela conta como ocupada (por exemplo, um jogo aberto). */
    static final int USO_ALTO = 70;
    /** O nvidia-smi mede uma média curta: logo depois da nossa inferência o uso ainda é nosso. */
    static final Duration RESSACA = Duration.ofSeconds(5);
    private static final int MAX_VALOR = 64;
    private static final int MAX_CABECALHO = 512;

    private static final Logger log = Logger.getLogger(EstadoDaIa.class.getName());

    private final Configuracao.ConfigIa config;
    private final LeitorDeGpu gpu;
    private final Atividade atividade;
    private final Clock relogio;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private String medida;
    private Instant medidaEm;
    private boolean gpuAvisada;

    EstadoDaIa(Configuracao.ConfigIa config, LeitorDeGpu gpu, Atividade atividade, Clock relogio) {
        this.config = config;
        this.gpu = gpu;
        this.atividade = atividade;
        this.relogio = relogio;
    }

    /** Nunca lança: um cabeçalho de estado não pode impedir a busca de comandos. */
    synchronized String cabecalho() {
        if (!config.ativo()) return "ativo=0";
        Instant agora = relogio.instant();
        if (medida == null || !agora.isBefore(medidaEm.plus(CACHE))) {
            medida = medir(agora);
            medidaEm = agora;
        }
        String completo = medida + ";inferindo=" + (atividade.inferindo() ? 1 : 0);
        if (completo.length() <= MAX_CABECALHO) return completo;
        return completo.substring(0, completo.lastIndexOf(';', MAX_CABECALHO));
    }

    private String medir(Instant agora) {
        List<String> pares = new ArrayList<>();
        par(pares, "ativo", "1");
        Boolean carregado = modeloCarregado();
        par(pares, "ollama", carregado == null ? "0" : "1");
        par(pares, "modelo", config.modelo());
        par(pares, "carregado", Boolean.TRUE.equals(carregado) ? "1" : "0");

        int[] placa = lerGpu();
        if (placa == null) {
            par(pares, "gpu", "0");
            par(pares, "ocupada", "0");
        } else {
            boolean nossa = atividade.inferindo()
                    || atividade.fimDaUltima().isAfter(agora.minus(RESSACA));
            par(pares, "gpu", "1");
            par(pares, "vramLivreMb", Integer.toString(Math.max(0, placa[1] - placa[0])));
            par(pares, "vramTotalMb", Integer.toString(placa[1]));
            par(pares, "gpuUso", Integer.toString(placa[2]));
            par(pares, "ocupada", placa[2] >= USO_ALTO && !nossa ? "1" : "0");
        }
        return String.join(";", pares);
    }

    /** Nulo = Ollama fora do ar; senão, se o modelo configurado está na memória. */
    private Boolean modeloCarregado() {
        try {
            HttpRequest pedido = HttpRequest.newBuilder(URI.create(config.url() + "/api/ps"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<InputStream> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofInputStream());
            byte[] corpo;
            try (InputStream entrada = resposta.body()) {
                corpo = entrada.readNBytes(256 * 1024);
            }
            if (resposta.statusCode() != 200) return null;
            // Os nomes do /api/ps só servem para comparar; nunca vão para o cabeçalho.
            for (JsonNode modelo : json.readTree(corpo).path("models")) {
                if (mesmoModelo(modelo.path("name").asString("")) || mesmoModelo(modelo.path("model").asString(""))) {
                    return true;
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** "qwen3.5" configurado sem tag é o "qwen3.5:latest" do Ollama. */
    private boolean mesmoModelo(String carregado) {
        String esperado = config.modelo().contains(":") ? config.modelo() : config.modelo() + ":latest";
        return carregado.equals(esperado) || carregado.equals(config.modelo());
    }

    /** {usada, total, uso%} da primeira GPU, ou nulo sem nvidia-smi. */
    private int[] lerGpu() {
        try {
            String linha = gpu.ler().strip().split("\\R", 2)[0];
            String[] partes = linha.split(",");
            if (partes.length != 3) throw new IOException("saída inesperada");
            int[] valores = new int[3];
            for (int i = 0; i < 3; i++) valores[i] = Integer.parseInt(partes[i].strip());
            if (valores[0] < 0 || valores[1] <= 0 || valores[2] < 0 || valores[2] > 100) {
                throw new IOException("valores fora da faixa");
            }
            return valores;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // Avisa uma vez só: sem nvidia-smi (GPU AMD, driver sem a ferramenta) é estado, não erro.
            if (!gpuAvisada) log.info("sem leitura da GPU pelo nvidia-smi: " + e.getClass().getSimpleName());
            gpuAvisada = true;
            return null;
        }
    }

    private static void par(List<String> pares, String chave, String valor) {
        pares.add(chave + "=" + limpo(valor));
    }

    /**
     * Só o que é seguro num valor de cabeçalho e no formato chave=valor. Um CR/LF chegando ao
     * HttpClient lançaria exceção a cada consulta, e o laço ficaria em recuo para sempre.
     */
    static String limpo(String valor) {
        String limpo = valor == null ? "" : valor.replaceAll("[^A-Za-z0-9._:/+-]", "");
        return limpo.length() <= MAX_VALOR ? limpo : limpo.substring(0, MAX_VALOR);
    }
}
