package dev.jarvis.agente;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class InstanciaUnicaTest {
    @Test void soUmaInstanciaEAQuedaNaoDeixaTrava(@TempDir Path pasta) throws Exception {
        try (var primeira = InstanciaUnica.tentar(pasta)) {
            assertNotNull(primeira); assertNull(InstanciaUnica.tentar(pasta));
        }
        try (var segunda = InstanciaUnica.tentar(pasta)) { assertNotNull(segunda); }
    }
}
