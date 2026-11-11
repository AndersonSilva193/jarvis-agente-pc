package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O X-Pc-Foco na consulta de comandos: vai com nome válido, some em qualquer outro caso. */
class ClienteJarvisFocoTest {

    private static Optional<String> foco(Supplier<String> fornecedor) {
        Configuracao config = new Configuracao(URI.create("https://jarvis.exemplo"), "segredo", null,
                Map.of(), false, false, "", 18, 0.6, "app", true);
        HttpRequest pedido = new ClienteJarvis(config, () -> "ativo=0", fornecedor)
                .comFoco(HttpRequest.newBuilder(URI.create("https://jarvis.exemplo/api/pc/comandos")))
                .GET().build();
        return pedido.headers().firstValue("X-Pc-Foco");
    }

    @Test
    @DisplayName("nome válido vai no cabeçalho")
    void nomeValidoVai() {
        assertEquals(Optional.of("Code"), foco(() -> "Code"));
        assertEquals(Optional.of("WINWORD"), foco(() -> "WINWORD"));
    }

    @Test
    @DisplayName("nulo, inválido ou fornecedor que lança: cabeçalho ausente")
    void foraDisso() {
        for (String ruim : new String[] {null, "", "a b", "x\r\nX-Evil: 1", "a".repeat(41)}) {
            assertTrue(foco(() -> ruim).isEmpty(), String.valueOf(ruim));
        }
        assertTrue(foco(() -> { throw new IllegalStateException("x"); }).isEmpty());
    }

    @Test
    @DisplayName("os construtores antigos não mandam o cabeçalho")
    void semFornecedor() {
        Configuracao config = new Configuracao(URI.create("https://jarvis.exemplo"), "segredo", null,
                Map.of(), false, false, "", 18, 0.6, "app", true);
        HttpRequest pedido = new ClienteJarvis(config)
                .comFoco(HttpRequest.newBuilder(URI.create("https://jarvis.exemplo/x"))).GET().build();
        assertEquals(List.of(), pedido.headers().allValues("X-Pc-Foco"));
    }
}
