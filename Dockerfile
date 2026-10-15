# Build stage: only Docker is needed on the host, no local JDK
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/accreditation-service-*.jar app.jar
EXPOSE 9999 9998
ENTRYPOINT ["java", "-Xms256m", "-Xmx384m", "-jar", "/app/app.jar"]
