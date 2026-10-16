package dev.jarvis.agente.gatilho;

import java.util.Locale;
import java.util.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * A captura do microfone, em 16 kHz mono 16 bits — o formato que tanto o {@link DetectorDePalmas}
 * quanto o Whisper esperam.
 *
 * <p>A linha é aberta e fechada de verdade. Em ATIVO o {@code Despertador} fecha, e isso não é só
 * economia de CPU: com o agente escutando ao mesmo tempo que a HUD, a própria voz do Jarvis saindo
 * nas caixas voltaria pelo microfone e alimentaria o detector.
 *
 * <p>Microfone que some no meio (USB desconectado, driver reiniciando) não derruba o agente: a
 * leitura falha, a linha é fechada e a reabertura é tentada de novo mais tarde. Um agente que
 * morresse por causa disso só voltaria no próximo logon.
 */
final class Microfone {

    private static final Logger log = Logger.getLogger(Microfone.class.getName());

    static final AudioFormat FORMATO = new AudioFormat(DetectorDePalmas.TAXA, 16, 1, true, false);

    /** Pedaços de 20 ms: curtos o bastante para o gatilho não atrasar, longos para não torrar CPU. */
    static final int AMOSTRAS_POR_LEITURA = DetectorDePalmas.TAXA * 20 / 1000;

    private final String nomeDesejado;
    private volatile TargetDataLine linha;
    private final byte[] bytes = new byte[AMOSTRAS_POR_LEITURA * 2];

    Microfone(String nomeDesejado) {
        this.nomeDesejado = nomeDesejado == null ? "" : nomeDesejado.trim();
    }

    boolean aberto() {
        TargetDataLine atual = linha;
        return atual != null && atual.isOpen();
    }

    /**
     * Abre a linha. Devolve falso quando não deu — sem exceção, porque quem chama vai só tentar
     * de novo daqui a pouco.
     */
    synchronized boolean abrir() {
        if (aberto()) {
            return true;
        }
        try {
            TargetDataLine nova = procurar();
            nova.open(FORMATO, bytes.length * 8);
            nova.start();
            linha = nova;
            log.info("microfone aberto" + (nomeDesejado.isEmpty() ? " (padrão do Windows)"
                    : " ('" + nomeDesejado + "')"));
            return true;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            log.warning("não consegui abrir o microfone: " + e.getMessage());
            fechar();
            return false;
        }
    }

    /**
     * A linha do microfone escolhido, ou a padrão do sistema.
     *
     * <p>A busca é por pedaço do nome, sem diferenciar maiúsculas: o nome completo que o Windows
     * dá a um dispositivo é comprido e muda entre máquinas, e ninguém vai querer copiá-lo inteiro
     * para o config.properties.
     */
    private TargetDataLine procurar() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMATO);
        if (!nomeDesejado.isEmpty()) {
            String procurado = nomeDesejado.toLowerCase(Locale.ROOT);
            for (Mixer.Info candidato : AudioSystem.getMixerInfo()) {
                if (!candidato.getName().toLowerCase(Locale.ROOT).contains(procurado)) {
                    continue;
                }
                Mixer mixer = AudioSystem.getMixer(candidato);
                if (mixer.isLineSupported(info)) {
                    return (TargetDataLine) mixer.getLine(info);
                }
            }
            log.warning("não achei microfone com '" + nomeDesejado + "'; usando o padrão");
        }
        return (TargetDataLine) AudioSystem.getLine(info);
    }

    /**
     * Lê o próximo pedaço para dentro de {@code destino}.
     *
     * @return quantas amostras foram lidas, ou -1 quando a linha caiu (e já foi fechada)
     */
    int ler(short[] destino) {
        if (!aberto()) {
            return -1;
        }
        try {
            TargetDataLine atual = linha;
            if (atual == null) return -1;
            int lidos = atual.read(bytes, 0, bytes.length);
            if (lidos <= 0) {
                return 0;
            }
            int amostras = lidos / 2;
            for (int i = 0; i < amostras; i++) {
                // Little-endian, como declarado no FORMATO.
                destino[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
            }
            return amostras;
        } catch (RuntimeException e) {
            log.warning("o microfone sumiu no meio da leitura: " + e);
            fechar();
            return -1;
        }
    }

    synchronized void fechar() {
        if (linha != null) {
            try {
                linha.stop();
                linha.close();
            } catch (RuntimeException e) {
                log.warning("erro ao fechar o microfone: " + e);
            }
            linha = null;
        }
    }
}
