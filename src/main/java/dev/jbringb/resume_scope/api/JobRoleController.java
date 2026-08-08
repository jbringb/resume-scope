package dev.jbringb.resume_scope.api;

import dev.jbringb.resume_scope.api.dto.JobRoleRequest;
import dev.jbringb.resume_scope.api.dto.JobRoleResponse;
import dev.jbringb.resume_scope.service.JobRoleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/job-roles")
@RequiredArgsConstructor
public class JobRoleController {

    private final JobRoleService jobRoleSvc;

    @GetMapping
    public Mono<List<JobRoleResponse>> list() {
        return jobRoleSvc.list();
    }

    @PostMapping
    public Mono<JobRoleResponse> create(@Valid @RequestBody JobRoleRequest body) {
        return jobRoleSvc.create(body);
    }

    @GetMapping("/{id}")
    public Mono<JobRoleResponse> get(@PathVariable UUID id) {
        return jobRoleSvc.get(id);
    }

    @PutMapping("/{id}")
    public Mono<JobRoleResponse> update(@PathVariable UUID id, @Valid @RequestBody JobRoleRequest body) {
        return jobRoleSvc.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return jobRoleSvc.delete(id);
    }
}
