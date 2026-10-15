FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 soundmatch
COPY --from=build /app/target/soundmatch-backend-0.1.0.jar app.jar
USER soundmatch
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
