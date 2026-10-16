package dev.jarvis.agente.gatilho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

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
                Configuracao.MODO_APP, true));
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
}
