# Build stage
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Run stage
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

# Healthcheck (burada ve docker-compose.yml) curl kullanır; Temurin 25 imajı curl içermez.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar

# Root olmayan kullanıcı; yazılabilir tek yer /tmp (compose'ta tmpfs).
RUN groupadd --system --gid 10001 hashmark     && useradd --system --uid 10001 --gid hashmark --no-create-home --shell /usr/sbin/nologin hashmark
USER 10001:10001

# Konteyner bellek sınırının %75'i heap; kalanı metaspace/thread/native için.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
