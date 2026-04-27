FROM gradle:8.14-jdk21-alpine AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew dependencies --no-daemon -q
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
