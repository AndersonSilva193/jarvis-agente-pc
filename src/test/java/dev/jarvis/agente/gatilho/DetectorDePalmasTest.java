package dev.jarvis.agente.gatilho;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.agente.gatilho.DetectorDePalmas.Disparo;

/**
 * O detector contra as cinco cenas de {@code src/test/resources}.
 *
 * <p>Os arquivos são sintetizados pelo {@link GeradorDeWavDeTeste} — determinísticos e exatos
 * sobre ataque, decaimento, timbre e ritmo, que é o que o detector julga. Não substituem uma
 * gravação da sua sala: para o mundo real existe o {@code --calibrar}.
 */
class DetectorDePalmasTest {

    /** A sensibilidade padrão do config.properties: nada de afrouxar o limiar para o teste passar. */
    private static final double SENSIBILIDADE_PADRAO = 18;

    private static short[] ler(String arquivo) throws IOException {
        try (InputStream entrada = DetectorDePalmasTest.class.getResourceAsStream("/" + arquivo)) {
            if (entrada == null) {
                throw new IOException("falta " + arquivo + "; rode o GeradorDeWavDeTeste");
            }
            ByteArrayOutputStream tudo = new ByteArrayOutputStream();
            entrada.transferTo(tudo);
            byte[] bytes = tudo.toByteArray();
            // Pula o cabeçalho de 44 bytes: o arquivo é sempre PCM 16 bits mono 16 kHz.
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 44, bytes.length - 44)
                    .order(ByteOrder.LITTLE_ENDIAN);
            short[] pcm = new short[(bytes.length - 44) / 2];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = buffer.getShort();
            }
            return pcm;
        }
    }

    /**
     * Toca o arquivo em pedaços de 20 ms, como o microfone entregaria.
     *
     * <p>Em pedaços, e não de uma vez, de propósito: o detector tem de atravessar a fronteira
     * entre blocos sem perder a conta do envelope.
     */
    private static Optional<Disparo> tocar(String arquivo) throws IOException {
        short[] pcm = ler(arquivo);
        DetectorDePalmas detector = new DetectorDePalmas(SENSIBILIDADE_PADRAO);
        int pedaco = DetectorDePalmas.TAXA * 20 / 1000;
        short[] bloco = new short[pedaco];
        for (int i = 0; i < pcm.length; i += pedaco) {
            int quantas = Math.min(pedaco, pcm.length - i);
            System.arraycopy(pcm, i, bloco, 0, quantas);
            Optional<Disparo> disparo = detector.alimentar(bloco, quantas);
            if (disparo.isPresent()) {
                return disparo;
            }
        }
        return Optional.empty();
    }

    @Test
    @DisplayName("duas palmas no intervalo certo disparam o gatilho")
    void duasPalmasDisparam() throws Exception {
        Optional<Disparo> disparo = tocar("duas-palmas.wav");

        assertTrue(disparo.isPresent(), "é exatamente o chamado que o usuário faz");
        assertTrue(disparo.get().acimaDoRuidoDb() >= SENSIBILIDADE_PADRAO,
                "o nível medido é o que o --calibrar mostra: " + disparo.get());
    }

    @Test
    @DisplayName("uma palma sozinha não dispara")
    void umaPalmaNaoDispara() throws Exception {
        // A porta batendo, o livro caindo: acontece o dia inteiro.
        assertFalse(tocar("uma-palma.wav").isPresent());
    }

    @Test
    @DisplayName("três palmas não disparam: aplauso não é chamado")
    void tresPalmasNaoDisparam() throws Exception {
        // A terceira chega antes de a espera vencer e cancela as duas primeiras.
        assertFalse(tocar("tres-palmas.wav").isPresent());
    }

    @Test
    @DisplayName("duas batidas na mesa no mesmo ritmo não disparam")
    void batidaNaMesaNaoDispara() throws Exception {
        // Mesmo ritmo das palmas: só a forma (sobe devagar) e o timbre (grave) as separam.
        assertFalse(tocar("batida-mesa.wav").isPresent());
    }

    @Test
    @DisplayName("fala normal perto do microfone não dispara")
    void falaNaoDispara() throws Exception {
        assertFalse(tocar("fala-normal.wav").isPresent());
    }
}
