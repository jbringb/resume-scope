# Requires build/libs/resume-scope.jar (see README). Temurin 25: no Java 26 JRE Alpine on Docker Hub yet.
FROM eclipse-temurin:25-jre-alpine AS layers
WORKDIR /app
COPY build/libs/resume-scope.jar resume-scope.jar
RUN java -Djarmode=layertools -jar resume-scope.jar extract

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=layers /app/dependencies/          ./
COPY --from=layers /app/spring-boot-loader/    ./
COPY --from=layers /app/snapshot-dependencies/ ./
COPY --from=layers /app/application/           ./

EXPOSE 8086

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
