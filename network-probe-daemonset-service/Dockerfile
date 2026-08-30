FROM eclipse-temurin:8-jre-alpine
RUN apk add --no-cache iperf3 iputils
WORKDIR /app
COPY target/network-probe-daemonset-service-*.jar app.jar
ENTRYPOINT ["sh", "-c", "iperf3 -s -D && exec java -jar /app/app.jar"]
