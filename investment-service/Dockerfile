# Use Java 17 to run the application
FROM eclipse-temurin:17-jre

# Set the application directory
WORKDIR /app

# Copy the compiled Investment Service JAR
COPY target/investment-service-0.0.1-SNAPSHOT.jar app.jar

# Document the Investment Service port
EXPOSE 8085

# Start the Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]