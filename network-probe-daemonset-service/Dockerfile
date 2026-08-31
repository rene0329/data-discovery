# Fabric8 uses the JVM TLS stack to call the Kubernetes API. The Java 8
# runtime negotiated no common TLS parameters with this cluster's API server,
# while the same Pod network path and service account work normally. Run the
# Java 8-compatible application on a current TLS-capable runtime.
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache iperf3 iputils
WORKDIR /app
COPY target/network-probe-daemonset-service-*.jar app.jar
ENTRYPOINT ["sh", "-c", "iperf3 -s -D && exec java -jar /app/app.jar"]
