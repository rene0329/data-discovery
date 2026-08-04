FROM openjdk:8u242-jre AS builder

WORKDIR /builder

COPY target/practice-server-1.0-SNAPSHOT-exec.jar app.jar

# Spring Boot 2.7 layered jars separate stable dependencies from changing application code.
# After the first pull, normal backend releases only replace the small application layer.
RUN java -Djarmode=layertools -jar app.jar extract


FROM openjdk:8u242-jre

WORKDIR /app

COPY --from=builder /builder/dependencies/ ./
COPY --from=builder /builder/spring-boot-loader/ ./
COPY --from=builder /builder/snapshot-dependencies/ ./
COPY --from=builder /builder/application/ ./

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "org.springframework.boot.loader.JarLauncher"]
