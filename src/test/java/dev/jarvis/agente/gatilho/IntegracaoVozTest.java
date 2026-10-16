package dev.jarvis.agente.gatilho;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import dev.jarvis.agente.Configuracao;

@EnabledIfSystemProperty(named = "teste.voz", matches = "true")
class IntegracaoVozTest {
    @Test void reconhecePalavraDeChamadoComModeloReal() throws Exception {
        Path runtime = Path.of(System.getProperty("teste.runtime"));
        var config = new Configuracao(URI.create("https://api.test"), "t", URI.create("https://hud.test"), Map.of(),
                true, true, "", 18, .6, "app", true, runtime.resolve("ferramentas/whisper/whisper-cli.exe"),
                runtime.resolve("modelos/ggml-base.bin"));
        try (var voz = ReconhecedorDeVoz.tentarCriar(config)) {
            assertNotNull(voz);
            assertTrue(ouvir(voz, runtime.resolve("chamado.wav")).isPresent());
            voz.reiniciar();
            assertTrue(ouvir(voz, runtime.resolve("negativo.wav")).isEmpty());
        }
    }
    private Optional<ReconhecedorDeVoz.Reconhecimento> ouvir(ReconhecedorDeVoz voz, Path arquivo) throws Exception {
        short[] silencio = new short[320];
        for (int i = 0; i < 55; i++) voz.alimentar(silencio, silencio.length);
        try (var entrada = AudioSystem.getAudioInputStream(arquivo.toFile())) {
            byte[] bytes = entrada.readAllBytes();
            for (int i = 0; i < bytes.length; i += 640) {
                int n = Math.min(640, bytes.length - i) / 2;
                short[] pcm = new short[n];
                for (int j = 0; j < n; j++) pcm[j] = (short) ((bytes[i+j*2] & 255) | bytes[i+j*2+1] << 8);
                var resultado = voz.alimentar(pcm, n);
                if (resultado.isPresent()) return resultado;
            }
        }
        for (int i = 0; i < 100; i++) {
            var resultado = voz.alimentar(silencio, silencio.length);
            if (resultado.isPresent()) return resultado;
            Thread.sleep(150);
        }
        return Optional.empty();
    }
}
