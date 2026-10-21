package dev.jarvis.agente;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.Set;
import java.util.logging.Logger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Não registra valores, caminhos, mensagens de bibliotecas nem o pedido completo. */
final class DiagnosticoEdicao {
    private static final Logger LOG = Logger.getLogger(DiagnosticoEdicao.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> OPERACOES = Set.of("atualizar_valor", "atualizar_celula",
            "atualizar_por_item", "preencher_celula_vazia", "acrescentar_linha", "inserir_linha",
            "inserir_coluna", "criar_aba", "expandir_filtro", "substituir_trecho",
            "substituir_todas_ocorrencias", "acrescentar_ao_final", "substituir_conteudo");
    private DiagnosticoEdicao() { }

    static Resultado resultado(ComandoRecebido comando, Exception falha) {
        String codigo, mensagem;
        if (falha instanceof FalhaEdicao conhecida) {
            codigo = conhecida.codigo(); mensagem = conhecida.getMessage();
        } else if (falha instanceof AccessDeniedException || falha instanceof SecurityException) {
            codigo = "ACESSO_NEGADO"; mensagem = "acesso ao arquivo negado; confira permissões e se outro programa o mantém aberto";
        } else if (falha instanceof NoSuchFileException) {
            codigo = "ARQUIVO_INDISPONIVEL"; mensagem = "arquivo necessário não encontrado; releia o catálogo";
        } else if (falha instanceof IOException) {
            codigo = "FALHA_DE_IO"; mensagem = "falha de leitura ou gravação; consulte o diagnóstico pelo ID do comando";
        } else {
            codigo = "FALHA_INTERNA"; mensagem = "falha interna na edição; consulte o diagnóstico pelo ID do comando";
        }
        int indice = falha instanceof FalhaEdicao conhecida ? conhecida.indiceOperacao() : -1;
        String etapa = switch (comando.acao()) {
            case "preparar_edicao", "aplicar_edicao", "preparar_desfazer", "aplicar_desfazer" -> comando.acao();
            default -> "desconhecida";
        };
        JsonNode operacoes = comando.dados() == null ? JSON.createArrayNode() : comando.dados().path("operacoes");
        String operacao = "nao_identificada";
        if (indice >= 0 && indice < operacoes.size()) {
            String tipo = operacoes.path(indice).path("tipo").asText("");
            if (OPERACOES.contains(tipo)) operacao = tipo;
        }
        ObjectNode dados = JSON.createObjectNode().put("codigoErro", codigo).put("etapa", etapa)
                .put("operacao", operacao).put("indiceOperacao", indice < 0 ? 0 : indice + 1)
                .put("classeExcecao", falha.getClass().getSimpleName());
        if (falha.getCause() != null) dados.put("classeCausa", falha.getCause().getClass().getSimpleName());
        LOG.warning("edicao recusada comando=" + comando.id() + " diagnostico=" + dados);
        return new Resultado(comando.id(), false, mensagem, null, null, dados);
    }
}
