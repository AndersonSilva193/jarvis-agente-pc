package dev.jarvis.agente.gatilho;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.jarvis.agente.Configuracao;

/**
 * O {@code --calibrar}: mede esta sala e sugere o {@code palmas.sensibilidadeDb}.
 *
 * <p>O limiar certo não existe em abstrato. Ele depende do ruído de fundo (ar-condicionado,
 * geladeira, rua), da distância até o microfone e do ganho do próprio microfone. Um valor bom na
 * mesa do escritório é surdo na sala grande, e frouxo no quarto de madrugada.
 *
 * <p>Então a receita é medir: cinco segundos de silêncio para achar o piso, cinco pares de palmas
 * para achar o pico, e a sugestão fica entre os dois — perto o bastante do pico para a palma
 * passar sempre, longe o bastante do piso para a conversa não passar nunca.
 *
 * <p>Fala por {@code System.out} e não pelo log: isto é uma conversa com quem está sentado na
 * frente do PC, com instruções que ele precisa ler na hora, não um registro para depois.
 */
public final class Calibrador {

    private static final int SEGUNDOS_DE_SILENCIO = 5;
    private static final int PARES = 5;
    /** O quanto um som precisa passar do piso para ser considerado "a palma do teste". */
    private static final double LIMIAR_DE_ESCUTA_DB = 8;
    /** Quanto tempo esperar cada par antes de desistir dele. */
    private static final long ESPERA_POR_PAR_MS = 15_000;
    /** Depois do primeiro estouro, por quanto tempo ainda procuramos o máximo do par. */
    private static final long JANELA_DO_PAR_MS = 1_500;

    /**
     * A fração do caminho entre o piso e o pico.
     *
     * <p>0,6 deixa 40% de folga até a palma mais fraca: palma de longe, palma de lado, palma com
     * a mão mais fechada. Abaixo disso começa a pegar porta batendo.
     */
    private static final double FRACAO_SUGERIDA = 0.6;

    private static final double MINIMO_SUGERIDO = 10;
    private static final double MAXIMO_SUGERIDO = 30;

    private final Configuracao config;

    public Calibrador(Configuracao config) {
        this.config = config;
    }

    /** Roda a calibração inteira. Volta sem fazer nada se o microfone não abrir. */
    public void rodar() throws InterruptedException {
        Microfone microfone = new Microfone(config.microfone());
        if (!microfone.abrir()) {
            System.out.println("Não consegui abrir o microfone. Confira 'microfone' no "
                    + "config.properties e as permissões de privacidade do Windows.");
            return;
        }
        try {
            System.out.println();
            System.out.println("=== Calibração do gatilho por palmas ===");
            System.out.println();

            double piso = medirSilencio(microfone);
            List<Double> picos = medirPalmas(microfone, piso);
            relatar(piso, picos);
        } finally {
            microfone.fechar();
        }
    }

    /** O piso: a mediana do silêncio, que ignora a tosse e a porta no meio da medição. */
    private double medirSilencio(Microfone microfone) {
        System.out.println("1) Fique em silêncio por " + SEGUNDOS_DE_SILENCIO + " segundos...");
        List<Double> janelas = new ArrayList<>();
        long ate = System.currentTimeMillis() + SEGUNDOS_DE_SILENCIO * 1000L;
        short[] bloco = new short[Microfone.AMOSTRAS_POR_LEITURA];
        while (System.currentTimeMillis() < ate) {
            int lidas = microfone.ler(bloco);
            if (lidas > 0) {
                janelas.add(DetectorDePalmas.decibeis(bloco, lidas));
            } else if (lidas < 0) {
                break;
            }
        }
        double piso = mediana(janelas);
        System.out.printf("   ruído de fundo: %.1f dBFS%n%n", piso);
        return piso;
    }

    /** Cinco pares de palmas, mostrando o pico de cada um. */
    private List<Double> medirPalmas(Microfone microfone, double piso)
            throws InterruptedException {
        List<Double> picos = new ArrayList<>();
        short[] bloco = new short[Microfone.AMOSTRAS_POR_LEITURA];

        for (int par = 1; par <= PARES; par++) {
            System.out.println("2." + par + ") Bata palmas DUAS vezes (par " + par + " de "
                    + PARES + ")...");
            Double pico = ouvirUmPar(microfone, bloco, piso);
            if (pico == null) {
                System.out.println("   não ouvi nada; seguindo.");
                continue;
            }
            picos.add(pico);
            System.out.printf("   pico: %.1f dBFS%n", pico);
            // Uma pausa para o eco morrer e o usuário se preparar para o próximo par.
            Thread.sleep(700);
            esvaziar(microfone, bloco);
        }
        System.out.println();
        return picos;
    }

    /** Espera um estouro e devolve o maior nível do par, ou nulo se o tempo acabou. */
    private Double ouvirUmPar(Microfone microfone, short[] bloco, double piso) {
        long desiste = System.currentTimeMillis() + ESPERA_POR_PAR_MS;
        Double pico = null;
        long fimDaJanela = 0;

        while (System.currentTimeMillis() < desiste) {
            int lidas = microfone.ler(bloco);
            if (lidas < 0) {
                return pico;
            }
            if (lidas == 0) {
                continue;
            }
            double db = DetectorDePalmas.decibeis(bloco, lidas);
            if (pico == null) {
                if (db >= piso + LIMIAR_DE_ESCUTA_DB) {
                    pico = db;
                    // A partir do primeiro estouro, ainda procuramos o máximo por um tempinho:
                    // o par tem duas palmas, e a segunda costuma ser a mais forte.
                    fimDaJanela = System.currentTimeMillis() + JANELA_DO_PAR_MS;
                }
            } else {
                pico = Math.max(pico, db);
                if (System.currentTimeMillis() >= fimDaJanela) {
                    return pico;
                }
            }
        }
        return pico;
    }

    /** Joga fora o que entrou enquanto o usuário se ajeitava. */
    private void esvaziar(Microfone microfone, short[] bloco) {
        long ate = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < ate && microfone.ler(bloco) >= 0) {
            // só consumir
        }
    }

    private void relatar(double piso, List<Double> picos) {
        if (picos.isEmpty()) {
            System.out.println("Não ouvi nenhuma palma. O microfone está captando? "
                    + "Teste em Configurações > Sistema > Som > Entrada.");
            return;
        }
        double picoMediano = mediana(picos);
        double distancia = picoMediano - piso;
        double sugerido = Math.round(Math.max(MINIMO_SUGERIDO,
                Math.min(MAXIMO_SUGERIDO, distancia * FRACAO_SUGERIDA)));

        System.out.println("=== Resultado ===");
        System.out.printf("  ruído de fundo ...... %.1f dBFS%n", piso);
        System.out.printf("  palmas (mediana) .... %.1f dBFS%n", picoMediano);
        System.out.printf("  diferença ........... %.1f dB%n", distancia);
        System.out.println();
        System.out.println("  Ponha no config.properties:");
        System.out.printf("      palmas.sensibilidadeDb=%.0f%n", sugerido);
        System.out.println();
        if (distancia < 15) {
            System.out.println("  Atenção: a diferença está pequena. Chegue mais perto do "
                    + "microfone, suba o volume de entrada dele ou desligue o que está fazendo "
                    + "barulho — senão vai haver engano dos dois lados.");
        }
        System.out.println("  Disparo demais? suba o número. Não dispara? baixe.");
    }

    static double mediana(List<Double> valores) {
        if (valores.isEmpty()) {
            return 0;
        }
        double[] ordenados = valores.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int meio = ordenados.length / 2;
        return ordenados.length % 2 == 1
                ? ordenados[meio]
                : (ordenados[meio - 1] + ordenados[meio]) / 2;
    }

    /** Só para o teste não precisar de microfone para conferir a conta. */
    static double sugerir(double piso, double... picos) {
        List<Double> lista = Arrays.stream(picos).boxed().toList();
        double distancia = mediana(lista) - piso;
        return Math.round(Math.max(MINIMO_SUGERIDO,
                Math.min(MAXIMO_SUGERIDO, distancia * FRACAO_SUGERIDA)));
    }
}
