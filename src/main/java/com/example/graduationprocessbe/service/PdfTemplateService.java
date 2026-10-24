package com.example.graduationprocessbe.service;

import com.example.graduationprocessbe.entity.Thesis;
import com.example.graduationprocessbe.exception.DocumentGenerationException;
import com.example.graduationprocessbe.exception.ResourceNotFoundException;
import com.example.graduationprocessbe.repository.ThesisRepository;
import com.example.graduationprocessbe.service.report.DocxPdfConverter;
import com.example.graduationprocessbe.service.report.DocxPlaceholderReplacer;
import com.example.graduationprocessbe.service.report.HtmlText;
import com.example.graduationprocessbe.service.report.ThesisReportData;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds the thesis report: loads the thesis from the database, fills the Word template
 * ({@code templates/template.docx}, placeholders written as {@code ${name}}) and converts the result to PDF.
 */
@Service
@RequiredArgsConstructor
public class PdfTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PdfTemplateService.class);
    private static final int MAX_DOCX_BYTES = 10 * 1024 * 1024;
    private static final String TEMPLATE_PATH = "templates/template.docx";
    private static final List<String> PROPOSAL_STEPS = List.of("submitProposal", "reviseProposal", "submitSignedProposal");

    private final ThesisRepository thesisRepository;
    private final ThesisProcessService thesisProcessService;
    private final JdbcTemplate jdbc;
    private final DocxPdfConverter converter = new DocxPdfConverter();

    /**
     * Generates the PDF report of a thesis.
     *
     * @throws ResourceNotFoundException     when the thesis does not exist
     * @throws org.springframework.security.access.AccessDeniedException when the caller may not see the thesis
     * @throws DocumentGenerationException   when the template is missing or cannot be filled or converted
     */
    @Transactional(readOnly = true)
    public byte[] generateThesisReport(String thesisId) {
        // authorises the caller exactly like the other thesis endpoints (owner, supervisor, faculty or admin)
        thesisProcessService.getThesis(thesisId);
        ThesisReportData data = loadData(thesisId);
        byte[] docx = fillTemplate(data);
        byte[] pdf = converter.convert(docx);
        log.info("Generated PDF report for thesis {} ({} bytes)", thesisId, pdf.length);
        return pdf;
    }

    /**
     * Converts a Word document that the caller has already filled in (e.g. the proposal or explanation built from the
     * admin-managed templates) into a PDF.
     *
     * @throws IllegalArgumentException    when the bytes are empty, too large or not a Word document
     * @throws DocumentGenerationException when the conversion itself fails
     */
    public byte[] convertDocxToPdf(byte[] docx) {
        requireWordDocument(docx);
        String sourceHash;
        try {
            sourceHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(docx));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        List<byte[]> cached = jdbc.query("SELECT pdf FROM document_pdf_cache WHERE source_hash=?", (rs, row) -> rs.getBytes(1), sourceHash);
        if (!cached.isEmpty()) return cached.getFirst();
        long started = System.currentTimeMillis();
        byte[] pdf = converter.convert(docx);
        jdbc.update("INSERT INTO document_pdf_cache(source_hash,pdf) VALUES(?,?) ON CONFLICT DO NOTHING", sourceHash, pdf);
        log.info("Converted Word document to PDF: {} -> {} bytes in {} ms", docx.length, pdf.length, System.currentTimeMillis() - started);
        return pdf;
    }

    /** A real .docx is a zip with word/document.xml; rejecting anything else early keeps docx4j errors readable. */
    private static void requireWordDocument(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Chưa có tệp Word để chuyển sang PDF");
        if (bytes.length > MAX_DOCX_BYTES) throw new IllegalArgumentException("Tệp Word lớn hơn 10MB");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("word/document.xml".equals(entry.getName())) return;
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Tệp không phải tài liệu Word (.docx) hợp lệ", ex);
        }
        throw new IllegalArgumentException("Tệp không phải tài liệu Word (.docx) hợp lệ");
    }

    ThesisReportData loadData(String thesisId) {
        Thesis thesis = thesisRepository.findById(thesisId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đề tài: " + thesisId));
        return new ThesisReportData(
                thesis.getId(),
                nullToEmpty(thesis.getTitle()),
                nullToEmpty(thesis.getTitleEn()),
                thesis.getLecturer() == null ? "" : nullToEmpty(thesis.getLecturer().getFullName()),
                students(thesis),
                HtmlText.toPlainText(latestProposal(thesis)),
                thesis.getCreatedDate() == null ? null : thesis.getCreatedDate().toLocalDate(),
                nullToEmpty(thesis.getCurrentStatus()));
    }

    /** Group members, the registering student first; falls back to the registering student when there are no member rows. */
    private List<ThesisReportData.Student> students(Thesis thesis) {
        List<ThesisReportData.Student> result = new ArrayList<>(jdbc.query(
                "SELECT u.full_name,u.username FROM members m JOIN users u ON u.id=m.user_id WHERE m.thesis_id=? "
                        + "ORDER BY (u.id=?) DESC,u.full_name",
                (rs, row) -> new ThesisReportData.Student(nullToEmpty(rs.getString(1)), nullToEmpty(rs.getString(2))),
                thesis.getId(), thesis.getStudent() == null ? "" : thesis.getStudent().getId()));
        if (result.isEmpty() && thesis.getStudent() != null) {
            result.add(new ThesisReportData.Student(nullToEmpty(thesis.getStudent().getFullName()), nullToEmpty(thesis.getStudent().getUsername())));
        }
        return result;
    }

    /** The newest proposal text: resubmissions are stored as submissions, the thesis only keeps the first version. */
    private String latestProposal(Thesis thesis) {
        String marks = String.join(",", PROPOSAL_STEPS.stream().map(step -> "?").toList());
        List<Object> params = new ArrayList<>(PROPOSAL_STEPS);
        params.add(thesis.getId());
        List<String> submissions = jdbc.queryForList(
                "SELECT content FROM thesis_submissions WHERE step_key IN (" + marks + ") AND thesis_id=? "
                        + "AND trim(content)<>'' ORDER BY submitted_at DESC LIMIT 1",
                String.class, params.toArray());
        return submissions.isEmpty() ? nullToEmpty(thesis.getDescription()) : submissions.getFirst();
    }

    private byte[] fillTemplate(ThesisReportData data) {
        try (InputStream template = new ClassPathResource(TEMPLATE_PATH).getInputStream();
             XWPFDocument document = new XWPFDocument(template);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new DocxPlaceholderReplacer(data.toPlaceholders()).replaceIn(document);
            document.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new DocumentGenerationException("Không đọc hoặc điền được mẫu báo cáo (" + TEMPLATE_PATH + ")", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
