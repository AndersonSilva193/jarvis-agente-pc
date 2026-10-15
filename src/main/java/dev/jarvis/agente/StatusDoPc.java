package dev.jarvis.agente;

import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Processador, memória, disco e bateria em uma linha curta.
 *
 * Curta de propósito: este texto volta ao Jarvis e entra no prompt do modelo, que o lê em voz
 * alta na HUD. Tudo em português do Brasil, com vírgula decimal.
 */
final class StatusDoPc {

    private static final Locale BR = Locale.of("pt", "BR");
    private static final double GB = 1024.0 * 1024 * 1024;
    /** Entre a primeira e a segunda medida de CPU: a primeira costuma vir sem valor. */
    private static final long INTERVALO_CPU_MS = 300;

    /** Constante, nunca concatenada — ver o javadoc de {@link Processos}. */
    private static final String BATERIA =
            "(Get-CimInstance Win32_Battery | Select-Object -First 1 "
            + "-ExpandProperty EstimatedChargeRemaining)";

    private StatusDoPc() {
    }

    static String medir() throws InterruptedException {
        var sistema = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();

        // A primeira leitura costuma vir negativa (ainda não há janela de amostragem).
        sistema.getCpuLoad();
        Thread.sleep(INTERVALO_CPU_MS);
        double cpu = sistema.getCpuLoad();

        double memoriaTotal = sistema.getTotalMemorySize() / GB;
        double memoriaUsada = memoriaTotal - sistema.getFreeMemorySize() / GB;

        StringBuilder linha = new StringBuilder();
        linha.append(cpu < 0 ? "CPU indisponível" : String.format(BR, "CPU %.0f%%", cpu * 100));
        linha.append(String.format(BR, ", memória %.1f/%.0f GB", memoriaUsada, memoriaTotal));
        linha.append(disco());
        linha.append(bateria());
        return linha.append('.').toString();
    }

    private static String disco() {
        try {
            FileStore sistemaDeArquivos = Files.getFileStore(Path.of("C:\\"));
            return String.format(BR, ", disco C: %.0f GB livres de %.0f",
                    sistemaDeArquivos.getUsableSpace() / GB, sistemaDeArquivos.getTotalSpace() / GB);
        } catch (Exception e) {
            return ", disco indisponível";
        }
    }

    /** Desktop não tem bateria, e isso é resposta, não erro. */
    private static String bateria() {
        try {
            Processos.Saida saida = Processos.powershell(BATERIA);
            if (!saida.ok() || saida.texto().isEmpty()) {
                return ", sem bateria (desktop)";
            }
            return ", bateria " + Integer.parseInt(saida.texto().trim()) + "%";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return ", sem bateria (desktop)";
        }
    }
}
