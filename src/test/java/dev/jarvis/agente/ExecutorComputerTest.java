package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

class ExecutorComputerTest {
    @Test void coordenadasFicamNoMonitorPrincipal() {
        Rectangle monitor = new Rectangle(1920, 0, 2560, 1440);
        assertArrayEquals(new int[] {1920, 0}, ExecutorComputer.escala(0, 0, monitor));
        assertThrows(IllegalArgumentException.class, () -> ExecutorComputer.escala(-1, 0, monitor));
        assertThrows(IllegalArgumentException.class, () -> ExecutorComputer.escala(1600, 0, monitor));
        assertThrows(IllegalArgumentException.class, () -> ExecutorComputer.escala(0, 900, monitor));
    }
    @Test void monitorRetratoTambemCabeNosLimitesDaImagem() {
        Rectangle monitor = new Rectangle(0, 0, 1440, 3200);
        assertThrows(IllegalArgumentException.class, () -> ExecutorComputer.escala(0, 2000, monitor));
        assertThrows(IllegalArgumentException.class, () -> ExecutorComputer.escala(901, 0, monitor));
    }
}
