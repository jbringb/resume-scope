package dev.jbringb.resume_scope.api;

import dev.jbringb.resume_scope.api.dto.CandidateResponse;
import dev.jbringb.resume_scope.service.CandidateService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/job-roles/{jobRoleId}/candidates")
@RequiredArgsConstructor
public class JobRoleCandidateController {

    private final CandidateService candidateService;

    @GetMapping
    public Mono<List<CandidateResponse>> list(@PathVariable UUID jobRoleId) {
        return candidateService.list(jobRoleId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<List<CandidateResponse>> upload(
            @PathVariable UUID jobRoleId, @RequestPart("files") Flux<FilePart> files) {
        return candidateService.uploadPdfs(jobRoleId, files);
    }

    @DeleteMapping("/{candidateId}")
    public Mono<Void> delete(@PathVariable UUID jobRoleId, @PathVariable UUID candidateId) {
        return candidateService.delete(jobRoleId, candidateId);
    }
}
