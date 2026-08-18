FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY src src
RUN chmod +x gradlew && ./gradlew clean test bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S viralground && adduser -S -G viralground viralground
COPY --from=build /app/build/libs/*.jar app.jar
USER viralground
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
