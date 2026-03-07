FROM openjdk:8u242-jre

WORKDIR /app

# spring-boot-maven-plugin classifier=exec 生成的 fat jar
COPY target/practice-server-1.0-SNAPSHOT-exec.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
