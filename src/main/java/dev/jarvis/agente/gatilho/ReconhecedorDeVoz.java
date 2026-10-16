package dev.jarvis.agente.gatilho;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import dev.jarvis.agente.Configuracao;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reconhecimento local em trabalhador único; nunca envia áudio nem conserva transcrições. */
final class ReconhecedorDeVoz implements AutoCloseable {
    record Reconhecimento(double confianca) {}
    interface Transcritor { Optional<Reconhecimento> transcrever(short[] pcm) throws Exception; }
    private static final Logger log = Logger.getLogger(ReconhecedorDeVoz.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ExecutorService trabalhador = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voz-offline"); t.setDaemon(true); return t;
    });
    private final Transcritor transcritor;
    private final short[] audio = new short[80_000];
    private final short[] anterior = new short[3_200];
    private int cursorAnterior, tamanho, silencio, atividade, aquecimento;
    private double piso = -60;
    private boolean ocupado, fechado;
    private long geracao;
    private long rodadaEmExecucao;
    private Reconhecimento resultado;
    private Process processo;

    ReconhecedorDeVoz(Transcritor transcritor) { this.transcritor = transcritor; }
    private ReconhecedorDeVoz(Configuracao config) {
        this.transcritor = pcm -> executar(config, pcm);
    }
    static ReconhecedorDeVoz tentarCriar(Configuracao config) {
        if (!Files.isRegularFile(config.vozExecutavel()) || !Files.isRegularFile(config.vozModelo())) {
            log.warning("voz offline indisponível: execute baixar-modelo.ps1 ao lado do config.properties");
            return null;
        }
        log.info("voz offline pronta: whisper.cpp, palavra de chamado Jarvis");
        return new ReconhecedorDeVoz(config);
    }
    synchronized Optional<Reconhecimento> alimentar(short[] pcm, int quantidade) {
        Reconhecimento pronto = resultado;
        resultado = null;
        if (fechado) return Optional.empty();
        if (pronto != null) return Optional.of(pronto);
        double db = DetectorDePalmas.decibeis(pcm, quantidade);
        if (aquecimento < 16_000) {
            piso += (db - piso) * .1;
            aquecimento += quantidade;
            guardarAnterior(pcm, quantidade);
            return Optional.empty();
        }
        boolean alto = db > Math.max(-65, piso + 8);
        if (tamanho == 0) {
            if (!alto || ocupado) {
                piso += (db - piso) * .02;
                guardarAnterior(pcm, quantidade);
                return Optional.empty();
            }
            for (int i = 0; i < anterior.length; i++) audio[i] = anterior[(cursorAnterior + i) % anterior.length];
            tamanho = anterior.length;
        }
        int copiar = Math.min(quantidade, audio.length - tamanho);
        System.arraycopy(pcm, 0, audio, tamanho, copiar);
        tamanho += copiar;
        if (alto) { atividade += copiar; silencio = 0; } else silencio += copiar;
        if (tamanho == audio.length || silencio >= 9_600) {
            if (atividade >= 3_200) enviar(Arrays.copyOf(audio, tamanho));
            Arrays.fill(audio, (short) 0);
            tamanho = silencio = atividade = 0;
        }
        guardarAnterior(pcm, quantidade);
        return Optional.empty();
    }
    private void guardarAnterior(short[] pcm, int quantidade) {
        for (int i = 0; i < quantidade; i++) {
            anterior[cursorAnterior] = pcm[i];
            cursorAnterior = (cursorAnterior + 1) % anterior.length;
        }
    }
    private void enviar(short[] pcm) {
        if (ocupado || fechado) return;
        ocupado = true;
        long rodada = geracao;
        rodadaEmExecucao = rodada;
        trabalhador.execute(() -> {
            try {
                Optional<Reconhecimento> ouvido = transcritor.transcrever(pcm);
                synchronized (this) {
                    if (!fechado && rodada == geracao) resultado = ouvido.orElse(null);
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                synchronized (this) {
                    if (!fechado && rodada == geracao) log.warning("falha na voz offline: " + e.getClass().getSimpleName());
                }
            } finally {
                Arrays.fill(pcm, (short) 0);
                synchronized (this) { ocupado = false; }
            }
        });
    }
    private Optional<Reconhecimento> executar(Configuracao config, short[] pcm) throws Exception {
        Path pasta = Files.createTempDirectory("jarvis-voz-");
        Path wav = pasta.resolve("entrada.wav");
        Path saida = pasta.resolve("resultado.json");
        Process atual = null;
        try {
            Files.write(wav, wav(pcm));
            synchronized (this) {
                if (fechado || rodadaEmExecucao != geracao || Thread.currentThread().isInterrupted()) return Optional.empty();
                atual = new ProcessBuilder(config.vozExecutavel().toString(), "-m", config.vozModelo().toString(),
                        "-f", wav.toString(), "-l", "pt", "-ojf", "-of", pasta.resolve("resultado").toString(),
                        "-t", "4", "-ng", "-np", "-nt", "-tp", "0", "-tpi", "0")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                processo = atual;
            }
            if (!atual.waitFor(15, TimeUnit.SECONDS)) throw new IOException("tempo excedido");
            if (atual.exitValue() != 0 || !Files.isRegularFile(saida)) throw new IOException("transcrição indisponível");
            return interpretar(Files.readString(saida), config.vozConfiancaMinima());
        } finally {
            if (atual != null && atual.isAlive()) { atual.destroyForcibly(); atual.waitFor(2, TimeUnit.SECONDS); }
            synchronized (this) { if (processo == atual) processo = null; }
            Files.deleteIfExists(saida);
            Files.deleteIfExists(wav);
            Files.deleteIfExists(pasta);
        }
    }
    static Optional<Reconhecimento> interpretar(String json, double minima) {
        try {
            JsonNode segmentos = JSON.readTree(json).path("transcription");
            StringBuilder texto = new StringBuilder();
            double soma = 0;
            int quantidade = 0;
            for (JsonNode segmento : segmentos) {
                texto.append(segmento.path("text").asString(""));
                for (JsonNode token : segmento.path("tokens")) {
                    if (token.path("id").asInt(50_257) >= 50_257) continue;
                    double p = token.path("p").asDouble(0);
                    if (!Double.isFinite(p) || p < 0 || p > 1) return Optional.empty();
                    String pedaco = token.path("text").asString("");
                    if (pedaco.replaceAll("[^\\p{L}\\p{N}]", "").isEmpty()) continue;
                    soma += p;
                    quantidade++;
                }
            }
            String frase = texto.toString().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim();
            // O Whisper base em português costuma transcrever o nome fora do vocabulário como "Jardes".
            if (frase.equals("jardes")) frase = "jarvis";
            if (!frase.equals("jarvis") || quantidade == 0 || soma / quantidade < minima) return Optional.empty();
            return Optional.of(new Reconhecimento(soma / quantidade));
        } catch (RuntimeException e) { return Optional.empty(); }
    }
    static byte[] wav(short[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + pcm.length * 2).putInt(0x45564157);
        b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1);
        b.putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(pcm.length * 2);
        for (short amostra : pcm) b.putShort(amostra);
        return b.array();
    }
    synchronized void reiniciar() {
        geracao++;
        resultado = null;
        tamanho = silencio = atividade = aquecimento = cursorAnterior = 0;
        piso = -60;
        Arrays.fill(audio, (short) 0); Arrays.fill(anterior, (short) 0);
        if (processo != null) processo.destroyForcibly();
    }
    public synchronized void close() {
        fechado = true; reiniciar(); trabalhador.shutdownNow();
    }
}
