# zhiguang_be 应用镜像 —— 多阶段构建
# 用法：docker compose up -d --build（compose 会自动构建）或 docker build -t zhiguang-app .
#
# 阶段 1：构建（Maven + JDK 21）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷 pom 预下载依赖（层缓存：pom 不变时后续构建不重下依赖）
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY src ./src
RUN mvn -B clean package -DskipTests

# 阶段 2：运行（精简 JRE 镜像）
FROM eclipse-temurin:21-jre-jammy

# curl 用于容器健康检查与排障；tzdata 保证时区正确
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行
RUN useradd -m -u 1001 app
WORKDIR /app
COPY --from=build /build/target/zhiguang-1.0-SNAPSHOT.jar /app/app.jar
USER app

ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=256m -Xss512k -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
