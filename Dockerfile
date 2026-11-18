FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S emori && adduser -S -G emori emori && mkdir -p /app/data/asmr && chown -R emori:emori /app
COPY --from=build --chown=emori:emori /workspace/build/libs/*.jar /app/emori.jar
USER emori
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/emori.jar"]
