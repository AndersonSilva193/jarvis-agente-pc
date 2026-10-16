package dev.jarvis.agente.gatilho;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "teste.windows", matches = "true")
class IntegracaoWindowsTest {
    @Test void dezCiclosComJanelaRealEmPerfilIsolado(@TempDir Path pasta) throws Exception {
        JanelasWindows windows = new JanelasWindows();
        String perfil = pasta.resolve("perfil-teste").toString();
        for (int ciclo = 0; ciclo < 10; ciclo++) {
            Process chrome = new ProcessBuilder("C:/Program Files/Google/Chrome/Application/chrome.exe",
                    "--app=about:blank", "--no-first-run", "--no-default-browser-check",
                    "--disable-background-mode", "--user-data-dir=" + perfil)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                long prazo = System.nanoTime() + 15_000_000_000L;
                List<Long> janelas;
                do { Thread.sleep(100); janelas = windows.localizar(perfil); }
                while (janelas.isEmpty() && System.nanoTime() < prazo);
                assertEquals(1, janelas.size(), "janela real precisa ser identificada pelo perfil");
                long handle = janelas.getFirst();
                windows.focar(handle);
                windows.fechar(handle);
                prazo = System.nanoTime() + 5_000_000_000L;
                do { Thread.sleep(100); janelas = windows.localizar(perfil); }
                while (!janelas.isEmpty() && System.nanoTime() < prazo);
                assertTrue(janelas.isEmpty(), "WM_CLOSE deve encerrar só a janela de teste");
            } finally {
                windows.localizar(perfil).forEach(windows::fechar);
                if (!chrome.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) chrome.destroy();
            }
        }
    }
}
