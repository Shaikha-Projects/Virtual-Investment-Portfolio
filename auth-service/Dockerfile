
# Use Java 17 to run the application
FROM eclipse-temurin:17-jre

# Create the application directory
WORKDIR /app

# Copy the compiled Spring Boot JAR
COPY target/auth-service-0.0.1-SNAPSHOT.jar app.jar

# Document the Auth Service port
EXPOSE 8084

# Start the Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
