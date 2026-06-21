package dev.jbringb.resume_scope.service;

import dev.jbringb.resume_scope.api.dto.CandidateResponse;
import dev.jbringb.resume_scope.db.generated.tables.records.CandidateRecord;
import dev.jbringb.resume_scope.pdf.PdfTextExtractor;
import dev.jbringb.resume_scope.repository.CandidateRepository;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class CandidateService {

    private final JobRoleRepository jobRoleRepo;
    private final CandidateRepository candidateRepo;
    private final PdfTextExtractor pdfTextExtractor;

    public Mono<List<CandidateResponse>> list(UUID jobRoleId) {
        return ensureJobRole(jobRoleId)
                .thenMany(candidateRepo.findByJobRoleId(jobRoleId))
                .map(this::toDto)
                .collectList();
    }

    public Mono<List<CandidateResponse>> uploadPdfs(UUID jobRoleId, Flux<FilePart> files) {
        return ensureJobRole(jobRoleId)
                .thenMany(files.concatMap(this::readFileBytes))
                .concatMap(fb -> extractText(fb.bytes())
                        .flatMap(text -> candidateRepo.insert(jobRoleId, fb.filename(), text))
                        .map(this::toDto))
                .collectList();
    }

    public Mono<Void> delete(UUID jobRoleId, UUID candidateId) {
        return ensureJobRole(jobRoleId)
                .then(candidateRepo.deleteByIdAndJobRoleId(candidateId, jobRoleId))
                .flatMap(deleted -> deleted
                        ? Mono.<Void>empty()
                        : Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found")));
    }

    private Mono<Void> ensureJobRole(UUID jobRoleId) {
        return jobRoleRepo
                .findById(jobRoleId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found")))
                .then();
    }

    // PDFBox parsing is blocking/CPU-bound — offload it (a legitimate use of boundedElastic).
    private Mono<String> extractText(byte[] bytes) {
        return Mono.fromCallable(() -> pdfTextExtractor.extract(bytes)).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<FileBytes> readFilePart(FilePart part) {
        return DataBufferUtils.join(part.content()).map(dataBuffer -> {
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);
            DataBufferUtils.release(dataBuffer);
            String name = part.filename() != null ? part.filename() : "cv.pdf";
            return new FileBytes(bytes, name);
        });
    }

    /** PDF parts only; reject empty or non-pdf names with a 400. */
    private Mono<FileBytes> readFileBytes(FilePart part) {
        String fn = part.filename() != null ? part.filename() : "";
        if (!fn.toLowerCase().endsWith(".pdf")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files: " + fn));
        }
        return readFilePart(part);
    }

    private CandidateResponse toDto(CandidateRecord r) {
        return new CandidateResponse(r.getId(), r.getJobRoleId(), r.getOriginalFilename(), r.getCreatedAt());
    }

    private record FileBytes(byte[] bytes, String filename) {}
}
