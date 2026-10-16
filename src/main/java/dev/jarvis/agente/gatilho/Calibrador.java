package dev.jarvis.agente.gatilho;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import dev.jarvis.agente.Configuracao;

/** Calibra pelo resultado do detector real, mantendo os trechos somente na memória. */
public final class Calibrador {
    private final Configuracao config;
    public Calibrador(Configuracao config) { this.config = config; }
    public void rodar() throws InterruptedException {
        Microfone microfone = new Microfone(config.microfone());
        if (!microfone.abrir()) { System.out.println("Microfone indisponível; confira o dispositivo e a permissão."); return; }
        try {
            short[] bloco = new short[320];
            System.out.println("Fique na distância de uso (até 2 metros). Aguarde cinco segundos em silêncio.");
            short[] ambiente = capturar(microfone, 5 * DetectorDePalmas.TAXA);
            List<Double> limites = new ArrayList<>();
            for (int par = 1; par <= 5; par++) {
                List<DetectorDePalmas> detectores = new ArrayList<>();
                for (int db = 6; db <= 30; db += 2) {
                    var detector = new DetectorDePalmas(db);
                    detector.alimentar(ambiente, ambiente.length);
                    detectores.add(detector);
                }
                var atual = new DetectorDePalmas(config.palmasSensibilidadeDb());
                atual.alimentar(ambiente, ambiente.length);
                System.out.println("Par " + par + "/5: bata duas palmas agora, com 200 a 700 ms entre elas.");
                long fim = System.nanoTime() + 15_000_000_000L;
                double maiorLimiar = 0;
                boolean reconhecidoAtual = false;
                var motivos = new TreeSet<String>();
                while (System.nanoTime() < fim) {
                    int lidas = microfone.ler(bloco);
                    if (lidas < 0) throw new IllegalStateException("microfone desconectado");
                    if (lidas == 0) { Thread.sleep(20); continue; }
                    reconhecidoAtual |= atual.alimentar(bloco, lidas).isPresent();
                    motivos.add(atual.diagnostico());
                    for (int i = 0; i < detectores.size(); i++) {
                        if (detectores.get(i).alimentar(bloco, lidas).isPresent()) {
                            maiorLimiar = Math.max(maiorLimiar, 6 + i * 2);
                            fim = Math.min(fim, System.nanoTime() + 300_000_000L);
                        }
                    }
                }
                System.out.println("  Detector atual: " + (reconhecidoAtual ? "PAR RECONHECIDO" : "NÃO RECONHECEU")
                        + "; observações: " + String.join(", ", motivos));
                if (maiorLimiar > 0) limites.add(maiorLimiar);
                else System.out.println("  Nenhum limiar reconheceu o ritmo e timbre; ajuste posição/ganho e repita.");
                System.out.println("  Aguarde um segundo antes do próximo par.");
                capturar(microfone, DetectorDePalmas.TAXA);
            }
            System.out.println("Pares reconhecidos: " + limites.size() + "/5.");
            if (limites.size() == 5) {
                double sugerido = Math.max(6, limites.stream().mapToDouble(Double::doubleValue).min().orElse(18) - 2);
                System.out.printf("Sugestão para essa distância: palmas.sensibilidadeDb=%.0f%n", sugerido);
                System.out.println("Valide conversa e ruídos antes de salvar. A configuração não foi alterada automaticamente.");
            } else System.out.println("Calibração incompleta: não vou sugerir um limiar com pares ausentes.");
            Arrays.fill(ambiente, (short) 0);
        } finally { microfone.fechar(); }
    }
    private short[] capturar(Microfone microfone, int tamanho) throws InterruptedException {
        short[] pcm = new short[tamanho], bloco = new short[320];
        int total = 0;
        long prazo = System.nanoTime() + 15_000_000_000L;
        while (total < tamanho && System.nanoTime() < prazo) {
            int lidas = microfone.ler(bloco);
            if (lidas < 0) throw new IllegalStateException("microfone desconectado");
            if (lidas == 0) { Thread.sleep(20); continue; }
            int copiar = Math.min(lidas, tamanho - total);
            System.arraycopy(bloco, 0, pcm, total, copiar); total += copiar;
        }
        if (total < tamanho) throw new IllegalStateException("microfone sem amostras");
        return pcm;
    }
    static double mediana(List<Double> valores) {
        if (valores.isEmpty()) return 0;
        double[] ordenados = valores.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int meio = ordenados.length / 2;
        return ordenados.length % 2 == 1 ? ordenados[meio] : (ordenados[meio - 1] + ordenados[meio]) / 2;
    }
    static double sugerir(double piso, double... picos) {
        return Math.round(Math.max(10, Math.min(30, (mediana(Arrays.stream(picos).boxed().toList()) - piso) * .6)));
    }
}
