package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RecusaDefinitivaTest {

    @Test void quatrocentosEDefinitivoPorqueInsistirNaoMudaONao() {
        for (int status : new int[] { 400, 404, 409, 413, 422 })
            assertTrue(RecusaDefinitiva.definitivo(status), "HTTP " + status);
    }

    @Test void tokenRitmoERedeContinuamSendoTentados() {
        // Descartar entrega por token mal copiado perderia print e diagnóstico por um segredo.
        for (int status : new int[] { 401, 403, 408, 429, 500, 502, 503, 204 })
            assertFalse(RecusaDefinitiva.definitivo(status), "HTTP " + status);
    }

    @Test void carregaOStatusParaOLog() {
        var recusa = new RecusaDefinitiva("resultado E2 HTTP 400", 400);
        assertEquals(400, recusa.status());
        assertEquals("resultado E2 HTTP 400", recusa.getMessage());
    }
}
