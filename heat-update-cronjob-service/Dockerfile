FROM openjdk:8u242-jre

WORKDIR /app

# 复制构建产物（先在宿主机 mvn package 后再 docker build）
COPY target/heat-update-cronjob-service-1.0-SNAPSHOT.jar app.jar

# CronJob 无需暴露端口，启动后执行完即退出
ENTRYPOINT ["java", \
  "-Xms64m", "-Xmx256m", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
