FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY src src
RUN chmod +x gradlew && ./gradlew clean test bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
WORKDIR /app
RUN addgroup -S viralground && adduser -S -G viralground viralground
COPY --from=build /app/build/libs/*.jar app.jar
USER viralground
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
