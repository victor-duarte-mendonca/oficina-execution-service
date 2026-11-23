# syntax=docker/dockerfile:1.7
# ===================================================
# Execution Service — build multi-stage (mesmo padrão do OS Service)
# ===================================================

# Stage 1: build. Tags de patch fixas: tag móvel tornaria o build não reproduzível.
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B --no-transfer-progress dependency:go-offline

# Os testes não rodam aqui: são o gate do job build-test do pipeline.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B --no-transfer-progress package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:21.0.5_11-jre-alpine

# UID/GID numéricos: o kubelet só valida runAsNonRoot com usuário numérico.
RUN addgroup -S -g 1001 oficina && adduser -S -u 1001 -G oficina -D oficina \
    && apk upgrade --no-cache

WORKDIR /app

# Heap pelo limite de memória do contêiner (cgroup), não por -Xmx fixo.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0" \
    JWT_PUBLIC_KEY_LOCATION=/app/keys/publicKey.pem

COPY --from=build --chown=1001:1001 /app/target/quarkus-app/lib/ /app/lib/
COPY --from=build --chown=1001:1001 /app/target/quarkus-app/*.jar /app/
COPY --from=build --chown=1001:1001 /app/target/quarkus-app/app/ /app/app/
COPY --from=build --chown=1001:1001 /app/target/quarkus-app/quarkus/ /app/quarkus/

USER 1001
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
