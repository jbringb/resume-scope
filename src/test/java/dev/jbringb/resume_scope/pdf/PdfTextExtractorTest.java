package dev.jbringb.resume_scope.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

// Was completely untested before this — PdfTextExtractor is on the CV-upload path for every
// candidate. Known gap: PDFBOX-6210 (CJK shared-glyph extraction, fixed in 3.0.8) isn't covered
// here since reproducing it needs an embedded CJK-capable font, not available as a test resource;
// this only locks in plain-text extraction and error handling.
class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extract_returnsPlainTextFromRealPdf() throws Exception {
        byte[] pdf = pdfWithText("Senior Java Engineer, 8 years Spring Boot experience.");

        String text = extractor.extract(pdf);

        assertThat(text).contains("Senior Java Engineer, 8 years Spring Boot experience.");
    }

    @Test
    void extract_throwsIOExceptionForCorruptBytes() {
        byte[] garbage = "not a pdf".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(garbage)).isInstanceOf(IOException.class);
    }

    private static byte[] pdfWithText(String text) throws IOException {
        try (PDDocument doc = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
