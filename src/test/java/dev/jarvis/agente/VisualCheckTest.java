package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/** Inspeção visual opcional do PDF exportado pelo LibreOffice. */
class VisualCheckTest {
    @Test void renderizarExportacaoDoLibreOffice() throws Exception {
        Path pdf = Path.of("target", "visual-check.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(pdf));
        try (var doc = Loader.loadPDF(pdf.toFile())) {
            assertTrue(doc.getNumberOfPages() >= 1);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                var pagina = new PDFRenderer(doc).renderImageWithDPI(i, 140);
                ImageIO.write(pagina, "png", Path.of("target", "visual-check-" + (i + 1)
                        + ".png").toFile());
            }
        }
    }
}
