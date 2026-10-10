package vn.bully.worker;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import java.awt.image.BufferedImage;
import com.google.protobuf.ByteString;
import vn.bully.worker.rpc.*;
import java.io.*;

class PdfServiceTest {
    static byte[] pdf(int pages) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(); document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    stream.newLineAtOffset(50, 700); stream.showText("SOURCE PAGE " + (i + 1)); stream.endText();
                    var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
                    image.setRGB(1, 1, 0xff0000); stream.drawImage(LosslessFactory.createFromImage(document, image), 50, 600, 40, 40);
                }
            }
            document.save(out); return out.toByteArray();
        }
    }
    @Test void mergePreservesAllPages() throws Exception {
        var r = PdfRequest.newBuilder().addFiles(ByteString.copyFrom(pdf(2))).addFiles(ByteString.copyFrom(pdf(3))).build();
        try (var result = Loader.loadPDF(new PdfService().process("Merge", r))) {
            assertEquals(5, result.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(result).contains("SOURCE PAGE 3"));
            assertTrue(result.getPage(0).getResources().getXObjectNames().iterator().hasNext());
        }
    }
    @Test void splitUsesInclusiveOneBasedRange() throws Exception {
        var r = PdfRequest.newBuilder().addFiles(ByteString.copyFrom(pdf(5))).setStartPage(2).setEndPage(4).build();
        try (var result = Loader.loadPDF(new PdfService().process("Split", r))) {
            assertEquals(3, result.getNumberOfPages());
            var text = new PDFTextStripper().getText(result);
            assertTrue(text.contains("SOURCE PAGE 2")); assertTrue(text.contains("SOURCE PAGE 4")); assertFalse(text.contains("SOURCE PAGE 1"));
        }
        assertThrows(IllegalArgumentException.class, () -> new PdfService().process("Split", r.toBuilder().setStartPage(0).build()));
    }
    @Test void watermarkAddsTextOnEveryPage() throws Exception {
        var r = PdfRequest.newBuilder().addFiles(ByteString.copyFrom(pdf(2))).setWatermark("BULLY DEMO").build();
        try (var result = Loader.loadPDF(new PdfService().process("Watermark", r))) {
            assertEquals(2, result.getNumberOfPages());
            String text = new PDFTextStripper().getText(result);
            assertTrue(text.contains("SOURCE PAGE 1"));
            assertEquals(2, text.split("BULLY DEMO", -1).length - 1);
        }
    }
    @Test void splitIncludesInheritedResources() throws Exception {
        byte[] bytes;
        try (var document = Loader.loadPDF(pdf(1)); var out = new ByteArrayOutputStream()) {
            document.getPages().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.RESOURCES, document.getPage(0).getResources());
            document.getPage(0).getCOSObject().removeItem(org.apache.pdfbox.cos.COSName.RESOURCES);
            document.save(out); bytes = out.toByteArray();
        }
        var request = PdfRequest.newBuilder().addFiles(ByteString.copyFrom(bytes)).setStartPage(1).setEndPage(1).build();
        try (var result = Loader.loadPDF(new PdfService().process("Split", request))) {
            assertTrue(new PDFTextStripper().getText(result).contains("SOURCE PAGE 1"));
        }
    }
    @Test void corruptPdfAndOversizedFilesAreRejected() {
        var r = PdfRequest.newBuilder().addFiles(ByteString.copyFromUtf8("not a pdf")).setStartPage(1).setEndPage(1).build();
        assertThrows(IOException.class, () -> new PdfService().process("Split", r));
        var huge = r.toBuilder().clearFiles().addFiles(ByteString.copyFrom(new byte[PdfService.MAX_FILE + 1])).build();
        assertThrows(IllegalArgumentException.class, () -> new PdfService().process("Split", huge));
    }
}
