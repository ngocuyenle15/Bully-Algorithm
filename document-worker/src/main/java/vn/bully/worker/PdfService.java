package vn.bully.worker;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import vn.bully.common.rpc.PdfRequest;
import java.io.*;
import java.util.ArrayList;

public final class PdfService {
    public static final int MAX_FILE = 10 * 1024 * 1024;
    public static final int MAX_TOTAL = 40 * 1024 * 1024;
    public byte[] process(String operation, PdfRequest r) throws IOException {
        if (r.getFilesCount() == 0) throw new IllegalArgumentException("Select PDF files");
        if (!operation.equals("Merge") && r.getFilesCount() != 1) throw new IllegalArgumentException("Select exactly one PDF");
        if (operation.equals("Merge") && r.getFilesCount() < 2) throw new IllegalArgumentException("Merge requires at least two PDFs");
        long total = 0;
        for (var file : r.getFilesList()) {
            if (file.isEmpty() || file.size() > MAX_FILE) throw new IllegalArgumentException("Each PDF must be between 1 byte and 10 MB");
            total += file.size();
        }
        if (total > MAX_TOTAL) throw new IllegalArgumentException("Total input exceeds 40 MB");
        var sources = new ArrayList<PDDocument>();
        try (var result = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (var file : r.getFilesList()) {
                var source = Loader.loadPDF(file.toByteArray());
                sources.add(source); // Imported resources may be lazy; keep sources open through save.
                if (operation.equals("Merge")) {
                    new PDFMergerUtility().appendDocument(result, source);
                } else {
                    int from = 1, to = source.getNumberOfPages();
                    if (operation.equals("Split")) {
                        from = r.getStartPage(); to = r.getEndPage();
                        if (from < 1 || to < from || to > source.getNumberOfPages())
                            throw new IllegalArgumentException("Invalid page range (pages are numbered from 1)");
                    }
                    for (int i = from - 1; i < to; i++) {
                        var original = source.getPage(i);
                        var page = result.importPage(original);
                        page.setResources(original.getResources()); // Include inherited page-tree resources.
                        if (operation.equals("Watermark")) watermark(result, page, r.getWatermark());
                    }
                }
            }
            result.save(out);
            if (out.size() > 60 * 1024 * 1024) throw new IllegalArgumentException("Output exceeds 60 MB");
            return out.toByteArray();
        } finally {
            for (var source : sources) source.close();
        }
    }
    private void watermark(PDDocument document, PDPage page, String text) throws IOException {
        if (text.isBlank() || text.length() > 200) throw new IllegalArgumentException("Watermark must contain 1–200 characters");
        PDFont font;
        File windowsFont = new File("C:/Windows/Fonts/arial.ttf");
        if (windowsFont.isFile()) font = PDType0Font.load(document, windowsFont);
        else font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (var stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            stream.setNonStrokingColor(0.75f, 0.75f, 0.75f);
            stream.beginText(); stream.setFont(font, 28);
            var box = page.getCropBox();
            stream.setTextMatrix(Matrix.getRotateInstance(Math.PI / 6, box.getLowerLeftX() + 35, box.getLowerLeftY() + box.getHeight() / 2));
            stream.showText(text); stream.endText();
        }
    }
}
