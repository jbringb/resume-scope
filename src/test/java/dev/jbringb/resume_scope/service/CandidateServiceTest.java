package dev.jbringb.resume_scope.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.jbringb.resume_scope.db.generated.tables.records.JobRoleRecord;
import dev.jbringb.resume_scope.pdf.PdfTextExtractor;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class CandidateServiceTest {

    @Mock
    JobRoleRepository jobRoleRepo;

    @Mock
    CandidateRepository candidateRepo;

    @Mock
    PdfTextExtractor pdfTextExtractor;

    CandidateService candidateSvc;

    @BeforeEach
    void setUp() {
        candidateSvc = new CandidateService(jobRoleRepo, candidateRepo, pdfTextExtractor);
    }

    // Real bytes over the 20MB cap, not a mocked exception — proves DataBufferUtils.join's
    // maxByteCount actually rejects an oversized upload instead of buffering it whole into heap.
    @Test
    void uploadPdfs_rejectsFileOverSizeLimit() {
        var jobRoleId = UUID.randomUUID();
        when(jobRoleRepo.findById(jobRoleId)).thenReturn(Mono.just(new JobRoleRecord()));

        var oversized = oversizedPdfPart("huge.pdf");

        assertThatThrownBy(() ->
                        candidateSvc.uploadPdfs(jobRoleId, Flux.just(oversized)).block())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
    }

    private static FilePart oversizedPdfPart(String filename) {
        FilePart part = mock(FilePart.class);
        when(part.filename()).thenReturn(filename);
        var factory = new DefaultDataBufferFactory();
        byte[] chunk = new byte[21 * 1024 * 1024]; // 1MB over the 20MB cap
        DataBuffer buffer = factory.wrap(chunk);
        when(part.content()).thenReturn(Flux.just(buffer));
        return part;
    }
}
