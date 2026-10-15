FROM openjdk:8u242-jre

WORKDIR /app

# SHA-256 verification uses the native OpenSSL implementation. Fail the image
# build instead of silently shipping an image that can only use the slow JVM 8
# fallback.
RUN test -x /usr/bin/openssl && /usr/bin/openssl version

# 复制构建产物（先在宿主机 mvn package 后再 docker build）
COPY target/data-discovery-daemonset-service-1.0-SNAPSHOT.jar app.jar

# 暴露服务端口
EXPOSE 8080

# Spring Boot 配置项可通过环境变量覆盖（SPRING_* / FILE_DISCOVERY_* / NODE_NAME 等）
ENTRYPOINT ["java", \
  "-Xms128m", "-Xmx400m", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
