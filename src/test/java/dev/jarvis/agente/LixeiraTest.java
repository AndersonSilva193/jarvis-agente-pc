package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A lixeira reversível.
 *
 * <p>O relógio dos testes é sempre {@code Clock.fixed} em UTC, para o nome da pasta datada não
 * depender do fuso da máquina que roda a suíte. Em produção o relógio é o do sistema, com o fuso
 * local, porque lá a pasta precisa bater com o dia que o usuário tem na cabeça.
 */
class LixeiraTest {
    @TempDir Path temporaria;
    private final JsonMapper json = JsonMapper.builder().build();

    private Path pasta() { return temporaria.resolve("documentos"); }

    private LixeiraDeArquivos lixeira(int dias, String instante) {
        return new LixeiraDeArquivos(pasta(), dias,
                Clock.fixed(Instant.parse(instante), ZoneOffset.UTC));
    }

    private String catalogar(String nome, String conteudo) throws Exception {
        Files.createDirectories(pasta());
        Files.writeString(pasta().resolve(nome), conteudo);
        var itens = new CatalogoDeArquivos(pasta()).listar(json.readTree("{}"));
        for (var item : itens.path("arquivos"))
            if (nome.equals(item.path("nome").asText())) return item.path("id").asText();
        throw new AssertionError("arquivo não listado: " + nome);
    }

    private Path registro(String data, String idLixeira) {
        return pasta().resolve(".lixeira").resolve(data).resolve(idLixeira + ".json");
    }

    private int catalogados() throws Exception {
        return new CatalogoDeArquivos(pasta()).listar(json.readTree("{}")).path("total").asInt();
    }

    @Test void moverTiraDoCatalogoEGravaRegistroAoLado() throws Exception {
        String conteudo = "Relatório de outubro";
        String id = catalogar("relatorio.txt", conteudo);
        var lixeira = lixeira(30, "2026-10-21T12:00:00Z");

        var saida = lixeira.mover(json.readTree("{\"id\":\"" + id + "\"}"));

        assertFalse(Files.exists(pasta().resolve("relatorio.txt")));
        assertEquals("relatorio.txt", saida.path("nome").asText());
        assertEquals("2026-10-21", saida.path("data").asText());
        assertEquals("2026-11-20", saida.path("expiraEm").asText());
        assertFalse(saida.path("alteradoDesdeOCadastro").asBoolean());
        String idLixeira = saida.path("idLixeira").asText();

        Path dia = pasta().resolve(".lixeira").resolve("2026-10-21");
        try (var filhos = Files.list(dia)) {
            var nomes = filhos.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(2, nomes.size(), "esperava registro e dados: " + nomes);
            assertTrue(nomes.contains(idLixeira + ".json"));
            assertTrue(nomes.contains(idLixeira + "-relatorio.txt.dados"));
        }
        assertEquals(conteudo, Files.readString(dia.resolve(idLixeira + "-relatorio.txt.dados")));

        // O requisito nas duas pontas: nem entrada órfã no catálogo, nem reimportação de dentro
        // da .lixeira pela listagem comum.
        assertEquals(0, catalogados());

        var lista = lixeira.listar(json.readTree("{}"));
        assertEquals(1, lista.path("total").asInt());
        assertEquals("na-lixeira", lista.path("itens").get(0).path("estado").asText());
        assertEquals(idLixeira, lista.path("itens").get(0).path("idLixeira").asText());
        assertEquals("2026-11-20", lista.path("itens").get(0).path("expiraEm").asText());
    }

    @Test void restaurarComNomeOcupadoGeraSufixoEAviso() throws Exception {
        String original = "Conteúdo original";
        String id = catalogar("nota.txt", original);
        var lixeira = lixeira(30, "2026-10-21T12:00:00Z");
        String idLixeira = lixeira.mover(json.readTree("{\"id\":\"" + id + "\"}"))
                .path("idLixeira").asText();
        Files.writeString(pasta().resolve("nota.txt"), "outro");

        var saida = lixeira.restaurar(json.readTree("{\"idLixeira\":\"" + idLixeira + "\"}"));

        assertTrue(saida.path("renomeado").asBoolean());
        assertEquals("nota-1.txt", saida.path("nome").asText());
        assertEquals("nota.txt", saida.path("nomeOriginal").asText());
        assertTrue(saida.path("aviso").asText().contains("já existia"));
        assertNotEquals(id, saida.path("id").asText(), "restaurar devolve id novo");
        assertEquals("outro", Files.readString(pasta().resolve("nota.txt")));
        assertEquals(original, Files.readString(pasta().resolve("nota-1.txt")));
        assertEquals(0, lixeira.listar(json.readTree("{}")).path("total").asInt());
    }

    @Test void idForaDaPastaOuInvalidoERecusado() throws Exception {
        var lixeira = lixeira(30, "2026-10-21T12:00:00Z");

        // Identificador que não tem a forma de um id de catálogo.
        assertThrows(IOException.class, () -> lixeira.mover(json.readTree("{\"id\":\"../x.txt\"}")));

        // Catálogo forjado à mão, apontando para fora da pasta: a lixeira não confia nele.
        Path fora = temporaria.resolve("fora.txt");
        Files.writeString(fora, "segredo");
        Files.createDirectories(pasta());
        String forjado = "11111111-2222-4333-8444-555555555555";
        String nome = Base64.getUrlEncoder().encodeToString(
                "..\\fora.txt".getBytes(StandardCharsets.UTF_8));
        Files.writeString(pasta().resolve(".jarvis-catalogo.properties"),
                forjado + "=" + nome + "|txt|7|0|" + EditorDeArquivos.hash(
                        "segredo".getBytes(StandardCharsets.UTF_8)) + "\n");
        assertThrows(IOException.class,
                () -> lixeira.mover(json.readTree("{\"id\":\"" + forjado + "\"}")));
        assertTrue(Files.exists(fora), "o arquivo de fora tem de continuar intacto");

        // Item de lixeira que não existe.
        assertThrows(IOException.class, () -> lixeira.restaurar(
                json.readTree("{\"idLixeira\":\"3f2a1b4c-5d6e-4f70-8a91-b2c3d4e5f601\"}")));

        // Registro adulterado depois de gravado: o nome é revalidado na restauração.
        Files.delete(pasta().resolve(".jarvis-catalogo.properties"));
        String id = catalogar("nota.txt", "texto");
        String idLixeira = lixeira.mover(json.readTree("{\"id\":\"" + id + "\"}"))
                .path("idLixeira").asText();
        Path arquivo = registro("2026-10-21", idLixeira);
        var r = (ObjectNode) json.readTree(Files.readString(arquivo));
        r.put("nome", "..\\escapou.txt");
        Files.writeString(arquivo, json.writeValueAsString(r));
        assertThrows(IOException.class, () -> lixeira.restaurar(
                json.readTree("{\"idLixeira\":\"" + idLixeira + "\"}")));
        assertFalse(Files.exists(temporaria.resolve("escapou.txt")));
    }

    @Test void expurgoApagaVencidoEPreservaDentroDoPrazo() throws Exception {
        // Os dois movimentos usam retenção enorme, senão o expurgo que cada um dispara na entrada
        // apagaria o item antigo antes de o teste chegar ao que quer medir.
        String antigo = catalogar("antigo.txt", "velho");
        lixeira(3650, "2026-09-01T12:00:00Z").mover(json.readTree("{\"id\":\"" + antigo + "\"}"));
        String recente = catalogar("recente.txt", "novo");
        lixeira(3650, "2026-10-20T12:00:00Z").mover(json.readTree("{\"id\":\"" + recente + "\"}"));

        Path raiz = pasta().resolve(".lixeira");
        Files.createDirectories(raiz.resolve("pasta-estranha"));
        Files.writeString(raiz.resolve("pasta-estranha").resolve("coisa.txt"), "nao e minha");
        Files.createDirectories(raiz.resolve("2099-01-01"));
        Files.writeString(raiz.resolve("2099-01-01").resolve("coisa.txt"), "do futuro");

        var lixeira = lixeira(30, "2026-10-21T12:00:00Z");
        assertEquals(1, lixeira.expurgar());

        assertFalse(Files.exists(raiz.resolve("2026-09-01")));
        assertTrue(Files.isDirectory(raiz.resolve("2026-10-20")));
        try (var filhos = Files.list(raiz.resolve("2026-10-20"))) {
            assertEquals(2, filhos.count());
        }
        // Nunca se apaga o desconhecido nem o que está no futuro.
        assertTrue(Files.exists(raiz.resolve("pasta-estranha").resolve("coisa.txt")));
        assertTrue(Files.exists(raiz.resolve("2099-01-01").resolve("coisa.txt")));

        assertEquals(1, lixeira.listar(json.readTree("{}")).path("total").asInt());
        assertEquals(0, lixeira.expurgar(), "expurgo é idempotente");
    }

    @Test void quedaEntreRegistroEMovimentoNaoPerdeArquivo() throws Exception {
        var lixeira = lixeira(30, "2026-10-21T12:00:00Z");
        String id = catalogar("nota.txt", "texto");

        // Caso A: o registro existe, o movimento nunca aconteceu.
        String orfao = "3f2a1b4c-5d6e-4f70-8a91-b2c3d4e5f601";
        Files.createDirectories(pasta().resolve(".lixeira").resolve("2026-10-21"));
        var pendente = json.createObjectNode();
        pendente.put("idLixeira", orfao).put("estado", "movendo").put("id", id)
                .put("nome", "nota.txt").put("formato", "txt").put("data", "2026-10-21");
        Files.writeString(registro("2026-10-21", orfao), json.writeValueAsString(pendente));

        lixeira.reconciliar();

        assertFalse(Files.exists(registro("2026-10-21", orfao)));
        assertEquals(0, lixeira.listar(json.readTree("{}")).path("total").asInt());
        assertTrue(Files.exists(pasta().resolve("nota.txt")), "o original não pode ter sumido");
        assertEquals(1, catalogados());

        // Caso B: o arquivo já foi movido, o registro ficou em "movendo".
        String idLixeira = lixeira.mover(json.readTree("{\"id\":\"" + id + "\"}"))
                .path("idLixeira").asText();
        Path arquivo = registro("2026-10-21", idLixeira);
        var r = (ObjectNode) json.readTree(Files.readString(arquivo));
        r.put("estado", "movendo");
        Files.writeString(arquivo, json.writeValueAsString(r));

        lixeira.reconciliar();

        var lista = lixeira.listar(json.readTree("{}"));
        assertEquals(1, lista.path("total").asInt());
        assertEquals("na-lixeira", lista.path("itens").get(0).path("estado").asText());
        assertEquals(0, catalogados());
    }
}
