FROM eclipse-temurin:21-jre

WORKDIR /app

ADD --checksum=sha256:bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

COPY build/libs/*.jar app.jar

EXPOSE 48080

ENTRYPOINT ["java", "-jar", "app.jar"]
