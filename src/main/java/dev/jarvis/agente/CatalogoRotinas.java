package dev.jarvis.agente;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Rotinas são cadastradas exclusivamente em arquivo local, nunca pela VPS. */
final class CatalogoRotinas {
    private static final Set<String> SENSIVEIS = Set.of("altera_codigo", "publica", "remove", "agentes", "executa_codigo");
    private static final Set<String> CAPACIDADES = Set.of("leitura", "altera_codigo",
            "altera_artefatos", "publica", "remove", "agentes", "executa_codigo", "rede");
    private final Map<String, Rotina> rotinas;
    private CatalogoRotinas(Map<String, Rotina> rotinas) { this.rotinas = Map.copyOf(rotinas); }
    static CatalogoRotinas vazio() { return new CatalogoRotinas(Map.of()); }
    static CatalogoRotinas de(Map<String, Rotina> rotinas) { return new CatalogoRotinas(rotinas); }
    Rotina obter(String nome) { return rotinas.get(nome); }
    List<Rotina> todas() { return List.copyOf(rotinas.values()); }

    static CatalogoRotinas ler(Path arquivo) throws IOException {
        if (!Files.exists(arquivo)) return vazio();
        Properties p = new Properties();
        try (Reader entrada = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) { p.load(entrada); }
        Map<String, Rotina> mapa = new LinkedHashMap<>();
        for (String chave : p.stringPropertyNames()) {
            if (!chave.startsWith("rotina.") || !chave.endsWith(".descricao")) continue;
            String nome = chave.substring(7, chave.length() - 10);
            if (!nome.matches("[a-z0-9][a-z0-9_.-]{0,31}"))
                throw new IOException("nome de rotina inválido: " + nome);
            String prefixo = "rotina." + nome + ".";
            String descricao = p.getProperty(chave, "").trim();
            if (descricao.isBlank() || descricao.length() > 160 || descricao.matches(".*[\\r\\n\\p{Cntrl}].*"))
                throw new IOException("descrição inválida da rotina " + nome);
            Path pasta = real(p.getProperty(prefixo + "pasta", ""), true, nome);
            Path programa = real(p.getProperty(prefixo + "programa", ""), false, nome);
            String tipo = p.getProperty(prefixo + "tipo", "executavel").trim();
            if (!Set.of("executavel", "maven", "claude_leitura").contains(tipo))
                throw new IOException("tipo inválido da rotina " + nome);
            String extensao = programa.getFileName().toString().toLowerCase(Locale.ROOT);
            if (tipo.equals("maven") ? !extensao.equals("mvn.cmd") : !extensao.endsWith(".exe"))
                throw new IOException("programa inválido da rotina " + nome + "; .cmd só no adaptador Maven");
            int timeout;
            try { timeout = Integer.parseInt(p.getProperty(prefixo + "timeoutSegundos", "0")); }
            catch (NumberFormatException e) { throw new IOException("timeout inválido da rotina " + nome, e); }
            if (timeout < 1 || timeout > 3600) throw new IOException("timeout fora de 1..3600: " + nome);
            String[] partes = p.getProperty(prefixo + "capacidades", "").split(",");
            List<String> capacidades = new ArrayList<>();
            for (String parte : partes) {
                String capacidade = parte.trim();
                if (!capacidade.matches("[a-z_]{3,30}")) throw new IOException("capacidade inválida: " + nome);
                if (!CAPACIDADES.contains(capacidade)) throw new IOException("capacidade desconhecida: " + nome);
                capacidades.add(capacidade);
            }
            if (capacidades.isEmpty()) throw new IOException("capacidade ausente: " + nome);
            if (tipo.equals("maven") && (!capacidades.contains("executa_codigo")
                    || !capacidades.contains("altera_artefatos")))
                throw new IOException("Maven exige executar código e alterar artefatos: " + nome);
            if (tipo.equals("claude_leitura") && (!capacidades.contains("leitura")
                    || !capacidades.contains("agentes") || capacidades.stream().anyMatch(c ->
                            Set.of("altera_codigo", "publica", "remove").contains(c))))
                throw new IOException("capacidades de Claude leitura incorretas: " + nome);
            String politica = p.getProperty(prefixo + "confirmacao", "").trim();
            if (!Set.of("sempre", "dispensada").contains(politica))
                throw new IOException("confirmação explícita obrigatória: " + nome);
            boolean confirmar = politica.equals("sempre");
            if (!confirmar && (capacidades.stream().anyMatch(SENSIVEIS::contains)
                    || tipo.equals("maven") || tipo.equals("claude_leitura")))
                throw new IOException("rotina com execução de código ou agente exige confirmação: " + nome);
            int quantidade;
            try { quantidade = Integer.parseInt(p.getProperty(prefixo + "argumentos.count", "0")); }
            catch (NumberFormatException e) { throw new IOException("lista de argumentos inválida: " + nome, e); }
            if (quantidade < 0 || quantidade > 20) throw new IOException("argumentos demais: " + nome);
            List<String> argumentos = new ArrayList<>();
            for (int i = 0; i < quantidade; i++) {
                String arg = p.getProperty(prefixo + "argumentos." + i);
                if (arg == null || arg.isBlank() || arg.length() > 256 || arg.indexOf('\0') >= 0)
                    throw new IOException("argumento fixo inválido da rotina " + nome);
                argumentos.add(arg);
            }
            if (tipo.equals("maven") && !argumentos.equals(List.of("-q", "-B", "test")))
                throw new IOException("adaptador Maven aceita somente -q, -B, test: " + nome);
            if (tipo.equals("claude_leitura") && !argumentos.isEmpty())
                throw new IOException("Claude leitura não aceita flags adicionais: " + nome);
            mapa.put(nome, new Rotina(nome, descricao, pasta, programa, List.copyOf(argumentos),
                    timeout, List.copyOf(capacidades), confirmar, tipo));
        }
        return new CatalogoRotinas(mapa);
    }
    private static Path real(String valor, boolean diretorio, String nome) throws IOException {
        if (valor == null || valor.isBlank()) throw new IOException("caminho ausente: " + nome);
        Path caminho = Path.of(valor.trim());
        if (!caminho.isAbsolute()) throw new IOException("caminho deve ser absoluto: " + nome);
        Path real = caminho.toRealPath();
        if (diretorio ? !Files.isDirectory(real) : !Files.isRegularFile(real))
            throw new IOException("caminho não existe: " + nome);
        return real;
    }
}
