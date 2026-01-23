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

@Service
@RequiredArgsConstructor
public class JobRoleService {

    private final JobRoleRepository jobRoleRepo;

    public List<JobRoleResponse> list() {
        return jobRoleRepo.findAll().stream().map(this::toDto).toList();
    }

    public JobRoleResponse get(UUID id) {
        return jobRoleRepo
                .findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found"));
    }

    public JobRoleResponse create(JobRoleRequest req) {
        var r = jobRoleRepo.insert(req.title(), req.description(), req.requirements());
        return toDto(r);
    }

    public JobRoleResponse update(UUID id, JobRoleRequest req) {
        if (!jobRoleRepo.update(id, req.title(), req.description(), req.requirements())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found");
        }
        return get(id);
    }

    public void delete(UUID id) {
        if (!jobRoleRepo.deleteById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job role not found");
        }
    }

    private JobRoleResponse toDto(JobRoleRecord r) {
        return new JobRoleResponse(r.getId(), r.getTitle(), r.getDescription(), r.getRequirements(), r.getCreatedAt());
    }
}
