package dev.jarvis.agente.gatilho;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Escreve os WAVs que o {@link DetectorDePalmasTest} usa.
 *
 * <p>Sintetizados, e não gravados: são determinísticos (semente fixa), cabem no repositório e
 * exercitam exatamente o que o detector julga — ataque, decaimento, timbre e ritmo. O que eles
 * <b>não</b> provam é o comportamento numa sala de verdade, com eco, ventilador e a distância do
 * microfone; para isso existe o {@code --calibrar}, e o README explica como trocar estes arquivos
 * por gravações suas mantendo os mesmos nomes.
 *
 * <p>Rode como {@code main} para regerar os arquivos em {@code src/test/resources}.
 */
final class GeradorDeWavDeTeste {

    private static final int TAXA = DetectorDePalmas.TAXA;
    /** Ruído de sala, bem abaixo de tudo: é o piso que o detector aprende no aquecimento. */
    private static final double RUIDO = 0.001;
    private static final double PICO_DA_PALMA = 0.5;
    private static final double PICO_DA_BATIDA = 0.5;

    private GeradorDeWavDeTeste() {
    }

    public static void main(String[] args) throws IOException {
        Path destino = Path.of(args.length > 0 ? args[0] : "src/test/resources");
        Files.createDirectories(destino);

        escrever(destino.resolve("duas-palmas.wav"), duasPalmas());
        escrever(destino.resolve("uma-palma.wav"), umaPalma());
        escrever(destino.resolve("tres-palmas.wav"), tresPalmas());
        escrever(destino.resolve("batida-mesa.wav"), batidaNaMesa());
        escrever(destino.resolve("fala-normal.wav"), falaNormal());
        System.out.println("WAVs de teste gerados em " + destino.toAbsolutePath());
    }

    // ------------------------------------------------------------------ cenas

    /** O gatilho: duas palmas com 350 ms entre elas e silêncio sobrando depois. */
    static short[] duasPalmas() {
        double[] trilha = sala(2500);
        palma(trilha, 600);
        palma(trilha, 950);
        return paraPcm(trilha);
    }

    /** Uma palma sozinha — a porta batendo, algo caindo. Não é chamado. */
    static short[] umaPalma() {
        double[] trilha = sala(2500);
        palma(trilha, 600);
        return paraPcm(trilha);
    }

    /** Três palmas: aplauso, não chamado. A terceira cancela as duas primeiras. */
    static short[] tresPalmas() {
        double[] trilha = sala(2500);
        palma(trilha, 600);
        palma(trilha, 950);
        palma(trilha, 1300);
        return paraPcm(trilha);
    }

    /**
     * Duas batidas na mesa no mesmo ritmo das palmas.
     *
     * <p>É o pior caso de propósito: só a forma e o timbre as separam de um chamado. Batida sobe
     * devagar (15 ms), cai devagar (60 ms de constante) e é grave.
     */
    static short[] batidaNaMesa() {
        double[] trilha = sala(2500);
        batida(trilha, 600);
        batida(trilha, 950);
        return paraPcm(trilha);
    }

    /** Alguém falando perto do microfone: envelope silábico lento e energia nos graves. */
    static short[] falaNormal() {
        double[] trilha = sala(2500);
        fala(trilha, 500, 1600);
        return paraPcm(trilha);
    }

    /**
     * O mesmo par de palmas, mas num silêncio digital — o que um microfone com supressão de ruído
     * entrega entre os sons.
     *
     * <p>Esta cena existe por um bug real: o fim do pico era medido só contra o piso de ruído, e
     * com piso de silêncio digital "voltar ao silêncio" passava a exigir uma queda de 90 dB, que
     * não cabe em {@value DetectorDePalmas#DECAIMENTO_MAXIMO_MS} ms. Nas outras cenas, de piso
     * -60 dBFS, o detector passava; no microfone de verdade ele recusava toda palma forte e
     * aceitava só as fracas, que tinham menos a cair. Nenhuma cena com ruído conseguia pegar isso.
     */
    static short[] duasPalmasEmSilencioDigital() {
        double[] trilha = new double[TAXA * 2500 / 1000];
        palma(trilha, 600);
        palma(trilha, 950);
        return paraPcm(trilha);
    }

    // ------------------------------------------------------------- ingredientes

    /** Uma sala vazia: só o ruído de fundo. */
    private static double[] sala(int duracaoMs) {
        Random sorte = new Random(20261015L);
        double[] trilha = new double[TAXA * duracaoMs / 1000];
        for (int i = 0; i < trilha.length; i++) {
            trilha[i] = sorte.nextGaussian() * RUIDO;
        }
        return trilha;
    }

    /**
     * Uma palma: estalo curto e agudo.
     *
     * <p>Ruído branco passado por uma diferença de primeira ordem (que realça o agudo e leva a
     * taxa de cruzamento por zero para perto de 0,5), com subida de 2 ms e queda exponencial de
     * 10 ms de constante — em 55 ms já voltou ao ruído.
     */
    private static void palma(double[] trilha, int emMs) {
        Random sorte = new Random(7_000L + emMs);
        int inicio = TAXA * emMs / 1000;
        int duracao = TAXA * 120 / 1000;
        double anterior = 0;
        for (int i = 0; i < duracao && inicio + i < trilha.length; i++) {
            double ms = i * 1000.0 / TAXA;
            double bruto = sorte.nextGaussian();
            double agudo = bruto - anterior;
            anterior = bruto;
            double envelope = ms < 2 ? ms / 2 : Math.exp(-(ms - 2) / 10.0);
            trilha[inicio + i] += agudo * 0.5 * PICO_DA_PALMA * envelope;
        }
    }

    /** Uma batida na mesa: grave, sobe em 15 ms e demora a morrer. */
    private static void batida(double[] trilha, int emMs) {
        int inicio = TAXA * emMs / 1000;
        int duracao = TAXA * 400 / 1000;
        for (int i = 0; i < duracao && inicio + i < trilha.length; i++) {
            double s = i / (double) TAXA;
            double ms = s * 1000;
            double corpo = Math.sin(2 * Math.PI * 80 * s) + 0.5 * Math.sin(2 * Math.PI * 150 * s);
            double envelope = ms < 15 ? ms / 15 : Math.exp(-(ms - 15) / 60.0);
            trilha[inicio + i] += corpo * 0.6 * PICO_DA_BATIDA * envelope;
        }
    }

    /**
     * Fala: harmônicos de uma voz (120 Hz de fundamental) modulados por sílabas a 4 Hz.
     *
     * <p>A energia fica nos graves, como em vogal — é por isso que a taxa de cruzamento por zero
     * da fala é baixa, e é isso que a separa do estalo da palma. O envelope silábico sobe em
     * dezenas de milissegundos, longe dos 5 ms de um ataque de palma.
     */
    private static void fala(double[] trilha, int emMs, int duracaoMs) {
        int inicio = TAXA * emMs / 1000;
        int duracao = TAXA * duracaoMs / 1000;
        for (int i = 0; i < duracao && inicio + i < trilha.length; i++) {
            double s = i / (double) TAXA;
            double voz = Math.sin(2 * Math.PI * 120 * s)
                    + 0.7 * Math.sin(2 * Math.PI * 240 * s)
                    + 0.4 * Math.sin(2 * Math.PI * 480 * s)
                    + 0.2 * Math.sin(2 * Math.PI * 960 * s);
            // Sílabas a 4 Hz, com as bordas suavizadas: nada de ataque instantâneo.
            double silaba = Math.pow(Math.max(0, Math.sin(2 * Math.PI * 4 * s)), 2);
            double entrada = Math.min(1, s / 0.15);
            trilha[inicio + i] += voz * 0.12 * silaba * entrada;
        }
    }

    // ------------------------------------------------------------------ arquivo

    private static short[] paraPcm(double[] trilha) {
        short[] pcm = new short[trilha.length];
        for (int i = 0; i < trilha.length; i++) {
            double limitado = Math.max(-1, Math.min(1, trilha[i]));
            pcm[i] = (short) Math.round(limitado * 32767);
        }
        return pcm;
    }

    /** WAV PCM 16 bits, mono, 16 kHz — o mesmo formato que o {@code Microfone} entrega. */
    static void escrever(Path arquivo, short[] pcm) throws IOException {
        int bytesDeDados = pcm.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + bytesDeDados).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes());
        buffer.putInt(36 + bytesDeDados);
        buffer.put("WAVE".getBytes());
        buffer.put("fmt ".getBytes());
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(TAXA);
        buffer.putInt(TAXA * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes());
        buffer.putInt(bytesDeDados);
        for (short amostra : pcm) {
            buffer.putShort(amostra);
        }
        Files.write(arquivo, buffer.array());
    }
}
