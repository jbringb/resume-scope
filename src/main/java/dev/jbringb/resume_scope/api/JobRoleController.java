package dev.jbringb.resume_scope.api;

import dev.jbringb.resume_scope.api.dto.JobRoleRequest;
import dev.jbringb.resume_scope.api.dto.JobRoleResponse;
import dev.jbringb.resume_scope.service.JobRoleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/job-roles")
@RequiredArgsConstructor
public class JobRoleController {

    private final JobRoleService jobRoleService;

    @GetMapping
    public Mono<List<JobRoleResponse>> list() {
        return Mono.fromCallable(jobRoleService::list).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<JobRoleResponse> create(@Valid @RequestBody JobRoleRequest body) {
        return Mono.fromCallable(() -> jobRoleService.create(body)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<JobRoleResponse> get(@PathVariable UUID id) {
        return Mono.fromCallable(() -> jobRoleService.get(id)).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<JobRoleResponse> update(@PathVariable UUID id, @Valid @RequestBody JobRoleRequest body) {
        return Mono.fromCallable(() -> jobRoleService.update(id, body)).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable UUID id) {
        return Mono.fromRunnable(() -> jobRoleService.delete(id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}