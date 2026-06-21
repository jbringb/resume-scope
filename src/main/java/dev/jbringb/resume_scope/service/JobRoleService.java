package dev.jbringb.resume_scope.service;

import dev.jbringb.resume_scope.api.dto.JobRoleRequest;
import dev.jbringb.resume_scope.api.dto.JobRoleResponse;
import dev.jbringb.resume_scope.db.generated.tables.records.JobRoleRecord;
import dev.jbringb.resume_scope.repository.JobRoleRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class JobRoleService {

    private final JobRoleRepository jobRoleRepo;

    public Mono<List<JobRoleResponse>> list() {
        return jobRoleRepo.findAll().map(this::toDto).collectList();
    }

    public Mono<JobRoleResponse> get(UUID id) {
        return jobRoleRepo.findById(id).map(this::toDto).switchIfEmpty(notFound());
    }

    public Mono<JobRoleResponse> create(JobRoleRequest req) {
        return jobRoleRepo
                .insert(req.title(), req.description(), req.requirements())
                .map(this::toDto);
    }

    public Mono<JobRoleResponse> update(UUID id, JobRoleRequest req) {
        return jobRoleRepo
                .update(id, req.title(), req.description(), req.requirements())
                .flatMap(updated -> updated ? get(id) : notFound());
    }

    public Mono<Void> delete(UUID id) {
        return jobRoleRepo.deleteById(id).flatMap(deleted -> deleted ? Mono.<Void>empty() : notFound());
    }

    private <T> Mono<T> notFound() {
        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found"));
    }

    private JobRoleResponse toDto(JobRoleRecord r) {
        return new JobRoleResponse(r.getId(), r.getTitle(), r.getDescription(), r.getRequirements(), r.getCreatedAt());
    }
}
