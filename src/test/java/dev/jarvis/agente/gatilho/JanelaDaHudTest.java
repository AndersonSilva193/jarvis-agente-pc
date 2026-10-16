package dev.jarvis.agente.gatilho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.agente.Configuracao;

/** A URL que o Chrome recebe, que é o que faz a HUD tocar a abertura em vez de só aparecer. */
class JanelaDaHudTest {

    /** O record direto, sem passar pelo arquivo: aqui só interessa a URL. */
    private JanelaDaHud comHud(String urlHud) {
        return new JanelaDaHud(new Configuracao(
                URI.create("https://jarvis.exemplo"), "segredo", URI.create(urlHud), Map.of(),
                true, true, "", Configuracao.SENSIBILIDADE_PADRAO, Configuracao.CONFIANCA_PADRAO,
                Configuracao.MODO_APP, true), new AmbienteFalso(), () -> 0);
    }

    @Test
    @DisplayName("acrescenta ?inicio=1 na URL limpa")
    void urlLimpa() {
        assertEquals("https://hud.exemplo?inicio=1", comHud("https://hud.exemplo").urlDeInicio());
    }

    @Test
    @DisplayName("respeita uma query que a URL já tenha, usando &")
    void urlComQuery() {
        // Um ? a mais quebraria a leitura do parâmetro e a HUD abriria sem a introdução.
        assertEquals("https://hud.exemplo/tela?tema=escuro&inicio=1",
                comHud("https://hud.exemplo/tela?tema=escuro").urlDeInicio());
    }

    @Test
    @DisplayName("o perfil é separado do Chrome do dia a dia")
    void perfilSeparado() {
        String perfil = JanelaDaHud.perfil();

        assertTrue(perfil.endsWith("JarvisHud") || perfil.endsWith(".jarvis-hud"), perfil);
        // É esse perfil próprio que faz o processo durar enquanto a janela existe — sem ele o
        // Chrome entregaria a URL à instância já aberta e morreria, e o agente acharia que a
        // tela fechou um segundo depois de abri-la.
        assertTrue(Path.of(perfil).isAbsolute(), perfil);
    }

    static final class AmbienteFalso implements JanelaDaHud.Ambiente {
        List<Long> janelas = new ArrayList<>();
        int aberturas, focos, fechamentos;
        boolean falhar;
        public List<Long> localizar() { return List.copyOf(janelas); }
        public void iniciar(String[] comando) throws IOException { if (falhar) throw new IOException(); aberturas++; }
        public void focar(long janela) { focos++; }
        public void fechar(long janela) { fechamentos++; }
    }
    private JanelaDaHud controlada(AmbienteFalso ambiente, AtomicLong hora) {
        return new JanelaDaHud(new Configuracao(URI.create("https://api.test"), "t", URI.create("https://hud.test"),
                Map.of("chrome", Path.of("chrome.exe")), true, true, "", 18, .6, "app", true), ambiente, hora::get);
    }
    @Test void concorrenciaAbreUmaVezESuspendeAntes() throws Exception {
        var ambiente = new AmbienteFalso(); var janela = controlada(ambiente, new AtomicLong());
        AtomicInteger suspensoes = new AtomicInteger();
        janela.acompanhar(() -> { assertEquals(0, ambiente.aberturas); suspensoes.incrementAndGet(); }, () -> {});
        try (var executor = Executors.newFixedThreadPool(3)) {
            var a = executor.submit(janela::abrir); var b = executor.submit(janela::abrir); var c = executor.submit(janela::abrir);
            a.get(); b.get(); c.get();
        }
        assertEquals(1, ambiente.aberturas); assertEquals(1, suspensoes.get());
        assertEquals(JanelaDaHud.Estado.ABRINDO, janela.estado());
    }
    @Test void adotaJanelaExistenteEFocaSemNovoProcesso() {
        var ambiente = new AmbienteFalso(); ambiente.janelas.add(42L);
        var janela = controlada(ambiente, new AtomicLong());
        janela.atualizar();
        assertEquals(JanelaDaHud.Estado.ATIVO, janela.estado());
        assertEquals(JanelaDaHud.Abertura.JA_EXISTENTE, janela.abrir());
        assertEquals(0, ambiente.aberturas); assertEquals(1, ambiente.focos);
    }
    @Test void fecharEsperaJanelaDesaparecerEUmSegundo() {
        var ambiente = new AmbienteFalso(); var hora = new AtomicLong(); var janela = controlada(ambiente, hora);
        AtomicInteger rearme = new AtomicInteger(); janela.acompanhar(() -> {}, rearme::incrementAndGet);
        janela.abrir(); ambiente.janelas.add(1L); janela.atualizar();
        janela.fechar(); hora.set(30_000); janela.atualizar();
        assertEquals(JanelaDaHud.Estado.FECHANDO, janela.estado()); assertEquals(0, rearme.get());
        ambiente.janelas.clear(); janela.atualizar(); hora.addAndGet(999); janela.atualizar();
        assertEquals(0, rearme.get()); hora.incrementAndGet(); janela.atualizar();
        assertEquals(1, rearme.get()); assertTrue(janela.emRepouso());
    }
    @Test void fechamentoManualEMinimizacao() {
        var ambiente = new AmbienteFalso(); var hora = new AtomicLong(); var janela = controlada(ambiente, hora);
        ambiente.janelas.add(1L); janela.atualizar(); hora.set(10_000); janela.atualizar();
        assertEquals(JanelaDaHud.Estado.ATIVO, janela.estado()); // HWND minimizado ainda enumerado.
        ambiente.janelas.clear(); janela.atualizar(); hora.addAndGet(1000); janela.atualizar();
        assertTrue(janela.emRepouso());
    }
    @Test void falhaEJanelaQueNuncaAparece() {
        var ambiente = new AmbienteFalso(); var hora = new AtomicLong(); var janela = controlada(ambiente, hora);
        ambiente.falhar = true; assertEquals(JanelaDaHud.Abertura.FALHA, janela.abrir());
        hora.set(1000); janela.atualizar(); assertTrue(janela.emRepouso());
        ambiente.falhar = false; janela.abrir(); hora.set(16_000); janela.atualizar();
        assertEquals(JanelaDaHud.Estado.FECHANDO, janela.estado());
        hora.set(17_000); janela.atualizar(); assertTrue(janela.emRepouso());
    }
    @Test void perfilExatoNaoConfundeChromePessoal() {
        assertTrue(JanelasWindows.usaPerfil("chrome.exe --user-data-dir=\"C:/JarvisHud\" --app=https://hud", "C:/JarvisHud"));
        org.junit.jupiter.api.Assertions.assertFalse(JanelasWindows.usaPerfil("chrome.exe --user-data-dir=C:/JarvisHudOutro", "C:/JarvisHud"));
        org.junit.jupiter.api.Assertions.assertFalse(JanelasWindows.usaPerfil("chrome.exe", "C:/JarvisHud"));
    }
}
