package dev.jbringb.resume_scope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ResumeScopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeScopeApplication.class, args);
    }
}
