package dev.jarvis.agente.gatilho;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reconhece duas palmas no áudio que entra, sem biblioteca nenhuma.
 *
 * <p>Não conhece microfone, thread nem arquivo: entram amostras, sai um {@link Disparo}. É isso
 * que o torna testável com WAV — e o que permite ao {@code Calibrador} usar o mesmo código que
 * roda de verdade.
 *
 * <h2>Como uma palma é reconhecida</h2>
 *
 * O enunciado pede janelas de 10 ms e ataque de menos de 5 ms, e as duas coisas não cabem juntas:
 * numa janela de 10 ms um ataque de 5 ms é meia amostra de informação. Então trabalhamos em dois
 * níveis na mesma passada:
 *
 * <ul>
 *   <li><b>Envelope de 1 ms</b> ({@value #AMOSTRAS_POR_MS} amostras): a energia sobe e desce aqui,
 *       e é daqui que saem o tempo de ataque e o de decaimento.</li>
 *   <li><b>Janela de 10 ms</b>: a taxa de cruzamento por zero, que mede o quanto o som é agudo.</li>
 * </ul>
 *
 * Uma palma é um estouro curto e agudo: sobe ao máximo em até {@value #ATAQUE_MAXIMO_MS} ms, volta
 * ao silêncio em até {@value #DECAIMENTO_MAXIMO_MS} ms e tem muito agudo. A batida na mesa e a
 * porta batendo são graves e sobem mais devagar; a nota de música demora a cair. Os três filtros
 * juntos — altura, forma e timbre — é que separam a palma do resto.
 *
 * <h2>Por que o ruído de fundo é móvel</h2>
 *
 * O limiar é relativo ao ruído, nunca absoluto: a mesma sala tem piso diferente com o ar ligado,
 * com a janela aberta ou de madrugada. A média móvel só é atualizada <b>fora</b> de um pico — se a
 * própria palma entrasse na conta, ela levantaria o piso e a segunda palma passaria batida.
 */
final class DetectorDePalmas {

    /** A taxa de amostragem que o {@code Microfone} entrega; tudo aqui depende dela. */
    static final int TAXA = 16_000;
    static final int AMOSTRAS_POR_MS = TAXA / 1000;
    /** A janela de 10 ms do enunciado, usada para o timbre. */
    static final int AMOSTRAS_POR_JANELA = 10 * AMOSTRAS_POR_MS;

    /** Do início da subida até o máximo. */
    static final int ATAQUE_MAXIMO_MS = 5;
    /** Do máximo até voltar perto do ruído. */
    static final int DECAIMENTO_MAXIMO_MS = 80;
    /** "Voltou ao silêncio" = chegou a este tanto acima do ruído. */
    static final double FIM_DO_PICO_DB = 6;
    /**
     * A queda a partir do máximo que também encerra o pico, quando o ruído é baixo demais.
     *
     * <p>Sozinho, o {@link #FIM_DO_PICO_DB} assume um piso de sala de verdade — algo perto de
     * -60 dBFS, que é o das cenas de {@code src/test/resources}. Microfone com supressão de ruído
     * entrega silêncio digital entre os sons, piso perto de -97 dBFS, e aí "voltar ao silêncio"
     * passa a exigir uma queda de 90 dB: com o decaimento de uma palma, isso leva mais de 100 ms
     * e estoura o orçamento de {@value #DECAIMENTO_MAXIMO_MS} ms <b>sempre</b>. O detector então
     * recusava justamente as palmas fortes, e aceitava só as fracas, que tinham menos a cair.
     *
     * <p>Medir a queda a partir do próprio máximo tira essa dependência: a palma acaba quando
     * acabou, com piso de -60 ou de -97.
     */
    static final double QUEDA_QUE_ENCERRA_O_PICO_DB = 20;
    /**
     * Cruzamentos por zero por amostra, na janela do pico.
     *
     * <p>0,25 equivale a um conteúdo dominante perto de 2 kHz. A palma é um estalo cheio de agudo
     * e passa com folga; a batida na mesa e o passo no chão são graves e ficam bem abaixo.
     */
    static final double CRUZAMENTOS_MINIMOS = 0.25;

    /** O intervalo aceito entre a primeira e a segunda palma. */
    static final long INTERVALO_MINIMO_MS = 200;
    static final long INTERVALO_MAXIMO_MS = 700;
    /** Depois da segunda palma, o tanto de silêncio que confirma que não vem uma terceira. */
    static final long ESPERA_POR_TERCEIRA_MS = 400;
    /** Depois de disparar, o detector fica surdo por este tempo. */
    static final long REFRATARIO_MS = 5_000;

    /** Enquanto o ruído de fundo não foi medido, nada dispara. */
    static final long AQUECIMENTO_MS = 300;

    /** Silêncio digital daria dB infinito; este é o piso. */
    private static final double PISO_DB = -100;
    /** A memória da média móvel: lenta depois de aquecida, rápida enquanto aprende. */
    private static final double ALFA = 0.02;
    private static final double ALFA_AQUECENDO = 0.2;
    /** Queda a partir do máximo que indica que o pico já passou. */
    private static final double QUEDA_QUE_CONFIRMA_O_PICO_DB = 3;

    /** O que o detector devolve quando as duas palmas aconteceram. */
    record Disparo(long emMs, double nivelDb, double ruidoDb) {

        /** Quanto a palma passou do ruído de fundo — o número que o {@code --calibrar} mostra. */
        double acimaDoRuidoDb() {
            return nivelDb - ruidoDb;
        }
    }

    private enum Fase {
        /** Esperando um pico. */
        PARADO,
        /** Subindo até o máximo. */
        SUBINDO,
        /** Já passou do máximo, caindo. */
        CAINDO,
        /** Era pico, mas não era palma: esperar o som acabar para voltar a escutar. */
        DESCARTANDO
    }

    private final double sensibilidadeDb;
    private final double quedaQueEncerraDb;

    /** Histórico curto de amostras, para medir o timbre depois que o pico passou (256 ms). */
    private final short[] historico = new short[4096];
    private long amostrasLidas;

    /** O resto de um sub-bloco de 1 ms que não fechou na chamada anterior. */
    private final short[] sobra = new short[AMOSTRAS_POR_MS];
    private int naSobra;

    private double ruidoDb = PISO_DB;
    private boolean aquecido;
    private int descartandoMs;
    private String diagnostico = "aquecendo";

    private Fase fase = Fase.PARADO;
    private long inicioDoPicoMs;
    private long picoMs;
    private double picoDb;

    private final List<Palma> palmas = new ArrayList<>();
    private long surdoAteMs;

    private record Palma(long emMs, double nivelDb) {
    }

    DetectorDePalmas(double sensibilidadeDb) {
        this(sensibilidadeDb, QUEDA_QUE_ENCERRA_O_PICO_DB);
    }

    /** Com a queda explícita: é como a varredura experimenta valores sobre o mesmo áudio. */
    DetectorDePalmas(double sensibilidadeDb, double quedaQueEncerraDb) {
        this.sensibilidadeDb = sensibilidadeDb;
        this.quedaQueEncerraDb = quedaQueEncerraDb;
    }

    void reiniciar() {
        java.util.Arrays.fill(historico, (short) 0);
        java.util.Arrays.fill(sobra, (short) 0);
        amostrasLidas = 0; naSobra = 0; ruidoDb = PISO_DB; aquecido = false;
        fase = Fase.PARADO; palmas.clear(); surdoAteMs = 0; descartandoMs = 0;
        diagnostico = "aquecendo";
    }
    String diagnostico() { return diagnostico; }

    /** O ruído de fundo medido até agora, em dBFS. O {@code --calibrar} vive disto. */
    double ruidoDb() {
        return ruidoDb;
    }

    /**
     * Consome um pedaço de áudio e diz se o gatilho aconteceu.
     *
     * @param amostras  PCM 16 bits, mono, {@value #TAXA} Hz
     * @param quantidade quantas posições de {@code amostras} valem
     */
    Optional<Disparo> alimentar(short[] amostras, int quantidade) {
        Disparo disparo = null;
        int lido = 0;
        while (lido < quantidade) {
            int cabe = Math.min(AMOSTRAS_POR_MS - naSobra, quantidade - lido);
            System.arraycopy(amostras, lido, sobra, naSobra, cabe);
            naSobra += cabe;
            lido += cabe;
            if (naSobra == AMOSTRAS_POR_MS) {
                Disparo deste = processarMilissegundo();
                naSobra = 0;
                if (deste != null && disparo == null) {
                    disparo = deste;
                }
            }
        }
        return Optional.ofNullable(disparo);
    }

    /** Um sub-bloco de 1 ms: guarda no histórico, mede, e deixa a máquina de estados andar. */
    private Disparo processarMilissegundo() {
        for (int i = 0; i < AMOSTRAS_POR_MS; i++) {
            historico[(int) ((amostrasLidas + i) % historico.length)] = sobra[i];
        }
        amostrasLidas += AMOSTRAS_POR_MS;
        long agoraMs = amostrasLidas / AMOSTRAS_POR_MS;
        double db = decibeis(sobra, AMOSTRAS_POR_MS);

        if (!aquecido) {
            ruidoDb = ruidoDb == PISO_DB ? db : mover(ruidoDb, db, ALFA_AQUECENDO);
            if (agoraMs >= AQUECIMENTO_MS) {
                aquecido = true;
            }
            return null;
        }

        avancarPico(agoraMs, db);
        return conferirPadrao(agoraMs);
    }

    /** A forma do pico: onde começou, onde foi o máximo e quando voltou ao silêncio. */
    private void avancarPico(long agoraMs, double db) {
        boolean alto = db >= ruidoDb + sensibilidadeDb;
        // O que vier primeiro: chegar ao piso da sala, ou cair o bastante do próprio máximo.
        boolean voltouAoSilencio = db <= ruidoDb + FIM_DO_PICO_DB
                || (fase != Fase.PARADO && db <= picoDb - quedaQueEncerraDb);

        switch (fase) {
            case PARADO -> {
                // Fora de pico é o único momento em que o piso de ruído se atualiza.
                if (!alto) ruidoDb = mover(ruidoDb, db, ALFA);
                diagnostico = alto ? "avaliando pico" : "abaixo do limiar";
                if (alto) {
                    fase = Fase.SUBINDO;
                    inicioDoPicoMs = agoraMs;
                    picoMs = agoraMs;
                    picoDb = db;
                }
            }
            case SUBINDO -> {
                if (db > picoDb) {
                    picoDb = db;
                    picoMs = agoraMs;
                } else if (db <= picoDb - QUEDA_QUE_CONFIRMA_O_PICO_DB) {
                    // O máximo já passou: dá para julgar o ataque.
                    fase = picoMs - inicioDoPicoMs <= ATAQUE_MAXIMO_MS
                            ? Fase.CAINDO
                            : Fase.DESCARTANDO;
                } else if (agoraMs - inicioDoPicoMs > ATAQUE_MAXIMO_MS + DECAIMENTO_MAXIMO_MS) {
                    // Som que fica alto sem nunca ter um máximo claro: música, voz, motor.
                    fase = Fase.DESCARTANDO;
                }
            }
            case CAINDO -> {
                if (voltouAoSilencio) {
                    if (agoraMs - picoMs <= DECAIMENTO_MAXIMO_MS && agudo()) {
                        registrarPalma(picoMs, picoDb);
                    }
                    fase = Fase.PARADO;
                } else if (agoraMs - picoMs > DECAIMENTO_MAXIMO_MS) {
                    fase = Fase.DESCARTANDO;
                }
            }
            case DESCARTANDO -> {
                diagnostico = "som longo ou ataque lento";
                // Uma mudança sustentada de ruído não pode prender o detector para sempre.
                if (++descartandoMs >= 300) ruidoDb = mover(ruidoDb, db, ALFA);
                if (voltouAoSilencio) {
                    fase = Fase.PARADO;
                    descartandoMs = 0;
                }
            }
        }
    }

    /**
     * O timbre, medido nos 10 ms que começam no início do pico.
     *
     * <p>A janela sai do histórico porque, quando esta pergunta é feita, o pico já passou — e é
     * justamente o começo dele que carrega o estalo.
     */
    private boolean agudo() {
        long inicio = (inicioDoPicoMs - 1) * AMOSTRAS_POR_MS;
        if (amostrasLidas - inicio > historico.length) {
            return false;
        }
        int cruzamentos = 0;
        short anterior = historico[(int) (inicio % historico.length)];
        for (int i = 1; i < AMOSTRAS_POR_JANELA; i++) {
            long posicao = inicio + i;
            if (posicao >= amostrasLidas) {
                break;
            }
            short atual = historico[(int) (posicao % historico.length)];
            if ((anterior >= 0) != (atual >= 0)) {
                cruzamentos++;
            }
            anterior = atual;
        }
        int medidas = (int) Math.min(AMOSTRAS_POR_JANELA, amostrasLidas - inicio);
        boolean aceito = medidas > 1 && (double) cruzamentos / (medidas - 1) >= CRUZAMENTOS_MINIMOS;
        diagnostico = aceito ? "palma reconhecida" : "timbre grave";
        return aceito;
    }

    /**
     * Anota uma palma — e ignora o que é a mesma palma de novo.
     *
     * <p>Encerrar o pico pela queda a partir do máximo faz o detector voltar a escutar enquanto a
     * reverberação ainda está acima do limiar. Esse rabo tem ataque instantâneo e timbre parecido,
     * então ele se registraria como uma segunda palma colada na primeira — e, pior, o par morreria
     * em {@link #conferirPadrao}, que descarta tudo quando o intervalo é menor que
     * {@value #INTERVALO_MINIMO_MS} ms. Resultado: a palma de verdade destruía o próprio chamado.
     *
     * <p>Duas batidas mais próximas que o intervalo mínimo nunca foram um chamado válido. A
     * diferença é que agora a segunda é <b>descartada</b> em vez de anular a primeira.
     */
    private void registrarPalma(long emMs, double nivelDb) {
        if (emMs < surdoAteMs) {
            return;
        }
        if (!palmas.isEmpty() && emMs - palmas.getLast().emMs() < INTERVALO_MINIMO_MS) {
            return;
        }
        palmas.add(new Palma(emMs, nivelDb));
    }

    /**
     * Duas palmas no intervalo certo, e silêncio depois.
     *
     * <p>A espera pela terceira é o que separa "chamei o Jarvis" de "a sala aplaudiu". Por isso a
     * decisão não sai no instante da segunda palma: ela sai {@value #ESPERA_POR_TERCEIRA_MS} ms
     * depois, se nada mais vier.
     */
    private Disparo conferirPadrao(long agoraMs) {
        if (palmas.size() >= 3) {
            // Aplauso, não chamado. Descarta tudo e espera a sala sossegar.
            palmas.clear();
            surdoAteMs = agoraMs + INTERVALO_MAXIMO_MS;
            return null;
        }
        if (palmas.size() == 1 && agoraMs - palmas.getFirst().emMs() > INTERVALO_MAXIMO_MS) {
            // Uma palma sozinha: alguém fechou a porta, derrubou algo. Esquece.
            palmas.clear();
            return null;
        }
        if (palmas.size() != 2) {
            return null;
        }
        long intervalo = palmas.get(1).emMs() - palmas.getFirst().emMs();
        if (intervalo < INTERVALO_MINIMO_MS || intervalo > INTERVALO_MAXIMO_MS) {
            palmas.clear();
            return null;
        }
        if (agoraMs - palmas.get(1).emMs() < ESPERA_POR_TERCEIRA_MS) {
            return null;
        }
        if (fase != Fase.PARADO) {
            // Silêncio é silêncio: há um pico em curso agora, e ele pode ser a terceira palma.
            // Sem esta linha a espera venceria antes de a terceira ser reconhecida — ela só é
            // registrada quando termina de cair, uns 55 ms depois do seu máximo —, e três palmas
            // bem ritmadas disparariam o gatilho que elas deveriam cancelar.
            return null;
        }
        double nivel = Math.max(palmas.getFirst().nivelDb(), palmas.get(1).nivelDb());
        palmas.clear();
        surdoAteMs = agoraMs + REFRATARIO_MS;
        return new Disparo(agoraMs, nivel, ruidoDb);
    }

    /** Energia do bloco em dBFS, com piso para o silêncio digital não virar menos infinito. */
    static double decibeis(short[] amostras, int quantidade) {
        double soma = 0;
        for (int i = 0; i < quantidade; i++) {
            double valor = amostras[i] / 32768.0;
            soma += valor * valor;
        }
        double rms = Math.sqrt(soma / quantidade);
        return rms <= 0 ? PISO_DB : Math.max(PISO_DB, 20 * Math.log10(rms));
    }

    private static double mover(double atual, double alvo, double alfa) {
        return atual + alfa * (alvo - atual);
    }
}
