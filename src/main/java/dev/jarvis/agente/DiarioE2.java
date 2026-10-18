package dev.jarvis.agente;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Um arquivo por ID para sobreviver ao reinício e impedir repetição após início registrado. */
final class DiarioE2 {
    record Registro(String id, String estado, String mensagem, Integer codigo,
            String imagemBase64, String arquivo, JsonNode dados, Instant atualizado, boolean enviado) { }
    private final Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();
    DiarioE2(Path pasta) throws IOException { this.pasta = pasta; Files.createDirectories(pasta); }
    synchronized Registro obter(String id) throws IOException {
        Path arquivo = caminho(id);
        if (!Files.exists(arquivo)) return null;
        JsonNode n = json.readTree(Files.readString(arquivo));
        return new Registro(id, n.path("estado").asText(), n.path("mensagem").asText(),
                n.path("codigo").isNumber() ? n.path("codigo").asInt() : null,
                n.path("imagemBase64").asText(""), n.path("arquivo").asText(""),
                n.path("dados"), Instant.parse(n.path("atualizado").asText()),
                n.path("enviado").asBoolean(false));
    }
    synchronized boolean recebido(String id) throws IOException {
        if (obter(id) != null) return false;
        guardar(new Registro(id, "recebido", "", null, "", "", null, Instant.now(), false));
        return true;
    }
    synchronized boolean iniciar(String id) throws IOException {
        Registro anterior = obter(id);
        if (anterior == null || !anterior.estado().equals("recebido")) return false;
        guardar(new Registro(id, "executando", "", null, "", "", null, Instant.now(), false));
        return true;
    }
    synchronized void concluir(String id, String estado, Resultado resultado, Integer codigo) throws IOException {
        guardar(new Registro(id, estado, resultado.mensagem(), codigo,
                resultado.imagemBase64() == null ? "" : resultado.imagemBase64(),
                resultado.arquivo() == null ? "" : resultado.arquivo().toString(),
                resultado.dados(), Instant.now(), false));
    }
    synchronized void incerto(String id) throws IOException {
        guardar(new Registro(id, "resultado_incerto", "início registrado sem resultado após reinício",
                null, "", "", null, Instant.now(), false));
    }
    synchronized void confirmarEnvio(String id) throws IOException {
        Registro r = obter(id);
        if (r != null) guardar(new Registro(id, r.estado(), r.mensagem(), r.codigo(),
                "", "", null, r.atualizado(), true));
    }
    synchronized List<Registro> pendentes() throws IOException {
        List<Registro> registros = new ArrayList<>();
        try (var arquivos = Files.list(pasta)) {
            for (Path caminho : arquivos.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Registro r = obter(caminho.getFileName().toString().replace(".json", ""));
                if (r != null && !r.enviado()) registros.add(r);
            }
        }
        return registros;
    }
    synchronized void removerAntigos() throws IOException {
        try (var arquivos = Files.list(pasta)) {
            for (Path caminho : arquivos.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Registro r = obter(caminho.getFileName().toString().replace(".json", ""));
                if (r != null && r.atualizado().isBefore(Instant.now().minus(Duration.ofDays(2))))
                    Files.deleteIfExists(caminho(r.id()));
            }
        }
    }
    private void guardar(Registro r) throws IOException {
        ObjectNode n = json.createObjectNode().put("estado", r.estado())
                .put("mensagem", r.mensagem()).put("atualizado", r.atualizado().toString())
                .put("imagemBase64", r.imagemBase64()).put("arquivo", r.arquivo())
                .put("enviado", r.enviado());
        if (r.codigo() != null) n.put("codigo", r.codigo());
        if (r.dados() != null) n.set("dados", r.dados());
        Path destino = caminho(r.id()); Path temporario = destino.resolveSibling(destino.getFileName() + ".tmp");
        Files.writeString(temporario, json.writeValueAsString(n));
        Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private Path caminho(String id) {
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("ID E2 inválido");
        return pasta.resolve(id + ".json");
    }
}
