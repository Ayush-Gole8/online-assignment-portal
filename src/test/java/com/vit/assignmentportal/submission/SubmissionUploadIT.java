package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The whole upload path with every layer real: the security filter chain, the controller, the
 * service, the volume, and MySQL.
 *
 * <p>This class is the only place the three-way agreement is provable. A unit test can show that
 * validation refuses a file; only a test that runs against a real filesystem and a real database
 * can show that a refusal left <em>neither</em> a row <em>nor</em> an orphaned file — which is the
 * property the requirement actually asks for, and the one a partial implementation would fail
 * while every unit test still passed.
 *
 * <p>Two habits are deliberate throughout. First, refusals are asserted twice: the response says
 * so, and the volume and the table are unchanged. Second, the row is read back through
 * {@link JdbcTemplate} rather than through the repository. Hibernate would rehydrate whatever was
 * written and might mask a mapping error; a raw {@code SELECT} shows what the database actually
 * holds.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubmissionUploadIT extends MySqlIntegrationTest {

    /**
     * A shared password for the accounts created here.
     *
     * <p>A fixture, not a credential: these accounts exist only inside the ephemeral Testcontainer
     * that this JVM starts and Ryuk destroys, and no code path outside this test class can reach
     * them ({@code SECURITY.md}).
     */
    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final String PDF = "application/pdf";

    /** Bytes that satisfy every validation rule: the right name, the right header, the right type. */
    private static final byte[] PDF_BYTES =
            ("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
             + "%%EOF\n").getBytes(StandardCharsets.US_ASCII);

    /** The same bytes with something changed, so the two uploads have different digests. */
    private static final byte[] OTHER_PDF_BYTES =
            ("%PDF-1.7\n2 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 2 0 R >>\n"
             + "%%EOF\n").getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------------
    // The happy path, and what it leaves behind
    // ------------------------------------------------------------------------

    @Test
    void storesTheUploadAndRedirectsToIt() throws Exception {
        SignedIn student = signIn("upload-happy");

        MvcResult result = mockMvc.perform(upload(student.session()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/submissions/*"))
                .andReturn();

        UUID submissionId = idFromRedirect(result);
        assertThat(rowsFor(student.studentId())).hasSize(1);
        assertThat(rowById(submissionId)).isPresent();
    }

    /**
     * The row must describe the file that is actually on the volume. Every field the database holds
     * is compared against a fact measured from the artefact itself: the size is the length of the
     * bytes on disk, and the checksum is computed here by an independent {@code MessageDigest}
     * rather than by the service that produced the stored value.
     *
     * <p>This is the assertion that would catch the most damaging kind of bug in this slice — a row
     * whose checksum or size describes a file that was never written, or one written from different
     * bytes. It would pass every unit test in the suite.
     */
    @Test
    void recordsMetadataThatDescribesTheFileThatWasActuallyStored() throws Exception {
        StoredRow row = storedRowAfterUpload(OTHER_PDF_BYTES, "coursework.pdf", PDF, "upload-fidelity");

        Path artefact = storageRoot().resolve(row.storedFileName());
        assertThat(artefact).as("the file named by the row is on the volume").isRegularFile();

        byte[] bytesOnDisk = Files.readAllBytes(artefact);
        assertThat(bytesOnDisk).isEqualTo(OTHER_PDF_BYTES);
        assertThat(row.fileSizeBytes()).isEqualTo(bytesOnDisk.length);
        assertThat(row.checksumSha256()).isEqualTo(sha256Of(bytesOnDisk));
        assertThat(row.checksumSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void recordsTheMetadataTheStudentEntered() throws Exception {
        StoredRow row = storedRowAfterUpload(PDF_BYTES, "essay one.pdf", PDF, "upload-metadata");

        assertThat(row.title()).isEqualTo("Essay one");
        assertThat(row.courseCode()).isEqualTo("CS101");
        assertThat(row.originalFileName()).isEqualTo("essay one.pdf");
        assertThat(row.contentType()).isEqualTo(PDF);
        assertThat(row.status()).isEqualTo("SUBMITTED");
        assertThat(row.version()).isZero();
    }

    /**
     * The owner comes from the authenticated principal, never from the request.
     *
     * <p>The upload posts no student identifier at all, so this asserts the stronger property: the
     * row's {@code student_id} is the id of the account that signed in, and the request had no way
     * to say otherwise.
     */
    @Test
    void recordsTheSignedInStudentAsTheOwner() throws Exception {
        SignedIn student = signIn("upload-owner");

        MvcResult result = mockMvc.perform(upload(student.session()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(rowById(idFromRedirect(result)))
                .hasValueSatisfying(row ->
                        assertThat(row.studentId()).isEqualTo(student.studentId().toString()));
    }

    @Test
    void leavesEverySubmissionInTheSubmittedStatus() throws Exception {
        StoredRow row = storedRowAfterUpload(PDF_BYTES, "essay.pdf", PDF, "upload-status");

        assertThat(row.status()).isEqualTo("SUBMITTED");
        assertThat(row.reviewedAt()).isNull();
        assertThat(row.reviewerId()).isNull();
        assertThat(row.rejectionReason()).isNull();
    }

    // ------------------------------------------------------------------------
    // Duplicate file names
    // ------------------------------------------------------------------------

    /**
     * Two students — or one student twice — handing in a file called {@code essay.pdf}.
     *
     * <p>The names the client chose are identical; the names on the volume are not. This is the
     * property that makes the server-generated identifier worth having: the second upload cannot
     * overwrite the first, and neither row can be read as describing the other's bytes.
     */
    @Test
    void keepsBothUploadsWhenTheSameFileNameIsUsedTwice() throws Exception {
        SignedIn student = signIn("upload-duplicate");

        String firstStored = storedRowAfterUploadIn(student, PDF_BYTES, "essay.pdf").storedFileName();
        String secondStored =
                storedRowAfterUploadIn(student, OTHER_PDF_BYTES, "essay.pdf").storedFileName();

        assertThat(firstStored).isNotEqualTo(secondStored);
        assertThat(storageRoot().resolve(firstStored)).isRegularFile();
        assertThat(storageRoot().resolve(secondStored)).isRegularFile();
        assertThat(Files.readAllBytes(storageRoot().resolve(firstStored))).isEqualTo(PDF_BYTES);
        assertThat(Files.readAllBytes(storageRoot().resolve(secondStored)))
                .isEqualTo(OTHER_PDF_BYTES);
        assertThat(rowsFor(student.studentId())).hasSize(2);
    }

    /**
     * The stored name is the identifier and nothing else — no original name, no extension, no
     * directory. Anything else would be a name derived from client input, which is the thing
     * {@code CLAUDE.md} §7 rule 5 forbids.
     */
    @Test
    void namesTheStoredFileWithABareServerGeneratedIdentifier() throws Exception {
        StoredRow row = storedRowAfterUpload(PDF_BYTES, "../../etc/passwd.pdf", PDF, "upload-name");

        assertThat(row.storedFileName())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        // The display name is normalised instead, so the row is still readable by a person.
        assertThat(row.originalFileName()).isEqualTo("passwd.pdf");
    }

    // ------------------------------------------------------------------------
    // Refusals: nothing stored, nothing recorded
    // ------------------------------------------------------------------------

    @Test
    void refusesAFileThatIsNotAPdf() throws Exception {
        assertRefusedWithNothingStored("upload-wrong-extension", PDF_BYTES, "essay.docx", PDF,
                "Only PDF files are accepted.");
    }

    /**
     * The case the extension check cannot see. The name says {@code .pdf}, the client says
     * {@code application/pdf}, and the bytes are text. Only the magic-byte check refuses it.
     */
    @Test
    void refusesPdfExtensionOnContentThatIsNotAPdf() throws Exception {
        assertRefusedWithNothingStored("upload-wrong-bytes",
                "not a pdf at all".getBytes(StandardCharsets.UTF_8), "essay.pdf", PDF,
                "That file is not a valid PDF.");
    }

    @Test
    void refusesAFileWhoseDeclaredContentTypeIsNotPdf() throws Exception {
        assertRefusedWithNothingStored("upload-wrong-type", PDF_BYTES, "essay.pdf", "text/html",
                "Only PDF files are accepted.");
    }

    /**
     * Over the limit, refused before a byte reaches the volume.
     *
     * <p>Well over the servlet container's {@code max-file-size} would be refused by Tomcat rather
     * than by this application, and {@code MockMvc} does not run the container's multipart parsing
     * — so the file is sized just above the application's own limit, which is the check this test
     * is about.
     */
    @Test
    void refusesAFileLargerThanTheLimit() throws Exception {
        byte[] oversized = new byte[(10 * 1024 * 1024) + 1];
        System.arraycopy(PDF_BYTES, 0, oversized, 0, PDF_BYTES.length);

        assertRefusedWithNothingStored("upload-oversized", oversized, "essay.pdf", PDF,
                "The file is larger than the 10 MB limit.");
    }

    @Test
    void refusesAFormSubmittedWithNoFile() throws Exception {
        SignedIn student = signIn("upload-no-file");
        List<Path> before = storedFiles();

        mockMvc.perform(multipart("/submissions")
                        .param("title", "Essay one")
                        .param("courseCode", "CS101")
                        .with(csrf())
                        .session(student.session()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/new"))
                .andExpect(flash().attribute("error", "Choose a PDF to upload."));

        assertThat(storedFiles()).isEqualTo(before);
        assertThat(rowsFor(student.studentId())).isEmpty();
    }

    @Test
    void refusesAnUploadWithNoTitle() throws Exception {
        SignedIn student = signIn("upload-no-title");
        List<Path> before = storedFiles();

        mockMvc.perform(multipart("/submissions")
                        .file(pdf("essay.pdf"))
                        .param("title", "   ")
                        .param("courseCode", "CS101")
                        .with(csrf())
                        .session(student.session()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/new"))
                .andExpect(flash().attribute("error", "A title is required."));

        assertThat(storedFiles()).isEqualTo(before);
        assertThat(rowsFor(student.studentId())).isEmpty();
    }

    @Test
    void refusesAnUploadWithNoCourseCode() throws Exception {
        SignedIn student = signIn("upload-no-course");
        List<Path> before = storedFiles();

        mockMvc.perform(multipart("/submissions")
                        .file(pdf("essay.pdf"))
                        .param("title", "Essay one")
                        .with(csrf())
                        .session(student.session()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/new"))
                .andExpect(flash().attribute("error", "A course code is required."));

        assertThat(storedFiles()).isEqualTo(before);
        assertThat(rowsFor(student.studentId())).isEmpty();
    }

    /**
     * Metadata is validated before the file, so a submission with a title longer than the column is
     * refused without the bytes ever being written. The ordering is the reason there is no file to
     * clean up, and this asserts it rather than assuming it.
     */
    @Test
    void refusesATitleLongerThanTheColumnAndStoresNoFile() throws Exception {
        SignedIn student = signIn("upload-long-title");
        List<Path> before = storedFiles();

        mockMvc.perform(multipart("/submissions")
                        .file(pdf("essay.pdf"))
                        .param("title", "t".repeat(201))
                        .param("courseCode", "CS101")
                        .with(csrf())
                        .session(student.session()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error",
                        "The title may be at most 200 characters."));

        assertThat(storedFiles()).isEqualTo(before);
        assertThat(rowsFor(student.studentId())).isEmpty();
    }

    /**
     * The refusal survives the redirect.
     *
     * <p>Asserting the flash attribute on the POST only proves it was set. Following the redirect
     * with the same session and reading the rendered page proves the mechanism — flash storage, the
     * {@code RedirectAttributes} resolver, and the template's {@code th:if} — is wired end to end.
     */
    @Test
    void showsTheReasonOnTheFormAfterARefusal() throws Exception {
        SignedIn student = signIn("upload-refusal-message");

        MvcResult refused = mockMvc.perform(multipart("/submissions")
                        .file(pdf("essay.docx"))
                        .param("title", "Essay one")
                        .param("courseCode", "CS101")
                        .with(csrf())
                        .session(student.session()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        MockHttpServletRequestBuilder followUp = get("/submissions/new")
                .session(student.session())
                .flashAttrs(refused.getFlashMap());

        mockMvc.perform(followUp)
                .andExpect(status().isOk())
                .andExpect(view().name("submission/upload"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Only PDF files are accepted.")));
    }

    /**
     * What the student typed is handed back so the form is not emptied by an error they have to
     * correct. The file part is not, and cannot be — a browser will not repopulate a file input.
     */
    @Test
    void returnsTheEnteredMetadataToTheFormAfterARefusal() throws Exception {
        SignedIn student = signIn("upload-form-repopulated");

        MvcResult refused = mockMvc.perform(multipart("/submissions")
                        .file(pdf("essay.docx"))
                        .param("title", "A title worth keeping")
                        .param("courseCode", "CS202")
                        .with(csrf())
                        .session(student.session()))
                .andReturn();

        mockMvc.perform(get("/submissions/new")
                        .session(student.session())
                        .flashAttrs(refused.getFlashMap()))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("A title worth keeping")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("CS202")));
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private org.springframework.test.web.servlet.RequestBuilder upload(MockHttpSession session) {
        return multipart("/submissions")
                .file(pdf("essay.pdf"))
                .param("title", "Essay one")
                .param("courseCode", "CS101")
                .with(csrf())
                .session(session);
    }

    private static MockMultipartFile pdf(String fileName) {
        return new MockMultipartFile("file", fileName, PDF, PDF_BYTES);
    }

    /** Posts an upload for a fresh student and returns the row that resulted. */
    private StoredRow storedRowAfterUpload(byte[] content, String fileName, String contentType,
                                           String emailSlug) throws Exception {
        return storedRowAfterUploadIn(signIn(emailSlug), content, fileName, contentType);
    }

    private StoredRow storedRowAfterUploadIn(SignedIn student, byte[] content, String fileName)
            throws Exception {
        return storedRowAfterUploadIn(student, content, fileName, PDF);
    }

    private StoredRow storedRowAfterUploadIn(SignedIn student, byte[] content, String fileName,
                                             String contentType) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/submissions")
                        .file(new MockMultipartFile("file", fileName, contentType, content))
                        .param("title", "Essay one")
                        .param("courseCode", "CS101")
                        .with(csrf())
                        .session(student.session()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/submissions/*"))
                .andReturn();

        return rowById(idFromRedirect(result))
                .orElseThrow(() -> new AssertionError("The upload did not create a row."));
    }

    /**
     * Posts an upload that must be refused, and asserts the whole refusal: the redirect, the
     * message, an unchanged volume, and an unchanged table.
     */
    private void assertRefusedWithNothingStored(String emailSlug, byte[] content, String fileName,
                                                String contentType, String expectedMessage)
            throws Exception {
        SignedIn student = signIn(emailSlug);
        List<Path> before = storedFiles();

        mockMvc.perform(multipart("/submissions")
                        .file(new MockMultipartFile("file", fileName, contentType, content))
                        .param("title", "Essay one")
                        .param("courseCode", "CS101")
                        .with(csrf())
                        .session(student.session()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/new"))
                .andExpect(flash().attribute("error", expectedMessage));

        assertThat(storedFiles())
                .as("a refused upload must not leave a file on the volume")
                .isEqualTo(before);
        assertThat(rowsFor(student.studentId()))
                .as("a refused upload must not leave a row")
                .isEmpty();
    }

    private UUID idFromRedirect(MvcResult result) {
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).as("a successful upload redirects to the submission")
                .startsWith("/submissions/");
        return UUID.fromString(location.substring("/submissions/".length()));
    }

    /** Signs in, creating the account on first use. */
    private SignedIn signIn(String slug) throws Exception {
        String email = slug + "@example.com";
        if (!userService.existsByEmail(email)) {
            userService.create(email, PASSWORD, Role.STUDENT);
        }
        UUID studentId = userService.findByEmail(email).orElseThrow().getId();

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .param("email", email)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        return new SignedIn(studentId, session);
    }

    private Optional<StoredRow> rowById(UUID id) {
        return jdbcTemplate.query("SELECT * FROM submissions WHERE id = ?", STORED_ROW, id.toString())
                .stream().findFirst();
    }

    private List<StoredRow> rowsFor(UUID studentId) {
        return jdbcTemplate.query("SELECT * FROM submissions WHERE student_id = ?", STORED_ROW,
                studentId.toString());
    }

    private static List<Path> storedFiles() {
        try (Stream<Path> entries = Files.list(storageRoot())) {
            return entries.sorted().toList();
        } catch (IOException cause) {
            throw new UncheckedIOException("Could not list the test storage root", cause);
        }
    }

    private static String sha256Of(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /**
     * A raw view of a {@code submissions} row.
     *
     * <p>Deliberately not {@link Submission}: reading through JPA would return whatever Hibernate
     * managed to rehydrate from its own mapping, which is the mapping under test. A {@code SELECT}
     * shows what the database was actually told.
     */
    private record StoredRow(String id, String studentId, String title, String courseCode,
                             String originalFileName, String storedFileName, String contentType,
                             long fileSizeBytes, String checksumSha256, String status,
                             String reviewedAt, String reviewerId, String rejectionReason,
                             long version) {
    }

    private static final RowMapper<StoredRow> STORED_ROW = (rs, rowNum) -> new StoredRow(
            rs.getString("id"),
            rs.getString("student_id"),
            rs.getString("title"),
            rs.getString("course_code"),
            rs.getString("original_file_name"),
            rs.getString("stored_file_name"),
            rs.getString("content_type"),
            rs.getLong("file_size_bytes"),
            rs.getString("checksum_sha256"),
            rs.getString("status"),
            rs.getString("reviewed_at"),
            rs.getString("reviewer_id"),
            rs.getString("rejection_reason"),
            rs.getLong("version"));

    private record SignedIn(UUID studentId, MockHttpSession session) {
    }
}
