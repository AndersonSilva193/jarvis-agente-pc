package dev.jarvis.agente;

/** O comando como a VPS o entrega. Nada de caminho de executável nem linha de comando: só nome. */
record ComandoRecebido(String id, String acao, String argumento) {
}
