package vn.bully.worker;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import java.nio.file.*;

/** Optional demo data generator, independent of the running Worker. */
public final class SamplePdfGenerator {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length == 0 ? "samples" : args[0]); Files.createDirectories(directory);
        for (int file = 1; file <= 2; file++) {
            try (var document = new PDDocument()) {
                for (int number = 1; number <= file + 1; number++) {
                    var page = new PDPage(); document.addPage(page);
                    try (var content = new PDPageContentStream(document, page)) {
                        content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 24);
                        content.newLineAtOffset(50, 720); content.showText("Distributed Document Processing"); content.endText();
                        content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16);
                        content.newLineAtOffset(50, 670); content.showText("Sample " + file + " - Page " + number); content.endText();
                        content.setNonStrokingColor(0.2f, 0.4f, 0.7f); content.addRect(50, 500, 250, 100); content.fill();
                    }
                }
                Path output = directory.resolve("sample-" + file + ".pdf"); document.save(output.toFile()); System.out.println(output.toAbsolutePath());
            }
        }
    }
}
