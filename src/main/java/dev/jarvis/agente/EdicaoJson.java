package dev.jarvis.agente;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Atualiza apenas escalares existentes; o endereço é um JSON Pointer RFC 6901. */
final class EdicaoJson {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Proposta(byte[] bytes, String previa) { }
    private EdicaoJson() { }

    static JsonNode analisar(byte[] bytes) throws IOException {
        try {
            JsonNode raiz = JSON.readTree(bytes);
            if (raiz == null) throw new IOException("JSON vazio");
            return raiz;
        } catch (RuntimeException e) { throw new IOException("JSON inválido ou com chaves duplicadas", e); }
    }

    static Proposta preparar(byte[] anterior, JsonNode operacoes) throws IOException {
        JsonNode raiz = analisar(anterior);
        List<String> previa = new ArrayList<>();
        for (JsonNode op : operacoes) {
            if (!"atualizar_valor".equals(texto(op, "tipo")))
                throw new IOException("operação JSON não suportada");
            String ponteiro = texto(op, "ponteiro");
            if (!ponteiro.startsWith("/") || ponteiro.length() > 512)
                throw new IOException("JSON Pointer inválido");
            String[] partes = ponteiro.substring(1).split("/", -1);
            JsonNode pai = raiz;
            for (int i = 0; i < partes.length - 1; i++) {
                pai = filho(pai, decodificar(partes[i]));
                if (pai == null) throw new IOException("caminho JSON inexistente");
            }
            String chave = decodificar(partes[partes.length - 1]);
            JsonNode atual = filho(pai, chave);
            if (atual == null) throw new IOException("caminho JSON inexistente");
            JsonNode esperado = op.path("valorAnterior");
            JsonNode novo = op.path("valorNovo");
            String tipoAnterior = texto(op, "tipoAnterior"), tipoNovo = texto(op, "tipoNovo");
            if (!escalar(atual) || !tipo(atual).equals(tipoAnterior) || !tipo(esperado).equals(tipoAnterior)
                    || !igual(atual, esperado)) throw new IOException("CONFLITO_DE_VALOR_JSON");
            if (!tipo(novo).equals(tipoNovo) || !escalar(novo))
                throw new IOException("tipo JSON incompatível");
            if (pai instanceof ObjectNode objeto) objeto.set(chave, novo.deepCopy());
            else if (pai instanceof ArrayNode lista) lista.set(indice(chave, lista.size()), novo.deepCopy());
            else throw new IOException("caminho JSON inexistente");
            previa.add(ponteiro + " (" + tipoAnterior + " → " + tipoNovo + "): "
                    + curto(atual) + " → " + curto(novo));
        }
        byte[] resultado = JSON.writeValueAsBytes(raiz);
        if (java.util.Arrays.equals(anterior, resultado) && previa.isEmpty())
            throw new IOException("nenhuma alteração proposta");
        return new Proposta(resultado, String.join("\n", previa)
                + "\nO serializador pode alterar espaços e apresentação lexical do JSON.");
    }

    private static String curto(JsonNode valor) {
        String texto = valor.toString();
        return texto.length() <= 160 ? texto : texto.substring(0, 159) + "…";
    }
    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        return valor.isTextual() ? valor.stringValue() : "";
    }
    private static String tipo(JsonNode no) {
        if (no == null || no.isMissingNode()) return "ausente";
        if (no.isTextual()) return "texto";
        if (no.isNumber()) return "numero";
        if (no.isBoolean()) return "booleano";
        if (no.isNull()) return "nulo";
        return "composto";
    }
    private static boolean escalar(JsonNode no) { return !"composto".equals(tipo(no)) && !"ausente".equals(tipo(no)); }
    private static boolean igual(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return new BigDecimal(a.toString())
                .compareTo(new BigDecimal(b.toString())) == 0;
        return a.equals(b);
    }
    private static String decodificar(String parte) throws IOException {
        for (int i = 0; i < parte.length(); i++) if (parte.charAt(i) == '~') {
            if (i + 1 >= parte.length() || (parte.charAt(i + 1) != '0' && parte.charAt(i + 1) != '1'))
                throw new IOException("escape JSON Pointer inválido");
            i++;
        }
        return parte.replace("~1", "/").replace("~0", "~");
    }
    private static int indice(String chave, int tamanho) throws IOException {
        if (!chave.matches("0|[1-9][0-9]*")) throw new IOException("índice JSON inválido");
        try {
            int i = Integer.parseInt(chave);
            if (i >= tamanho) throw new IOException("caminho JSON inexistente");
            return i;
        } catch (NumberFormatException e) { throw new IOException("índice JSON inválido", e); }
    }
    private static JsonNode filho(JsonNode pai, String chave) throws IOException {
        if (pai instanceof ObjectNode) {
            JsonNode valor = pai.path(chave);
            return valor.isMissingNode() ? null : valor;
        }
        if (pai instanceof ArrayNode lista) return lista.get(indice(chave, lista.size()));
        return null;
    }
}
