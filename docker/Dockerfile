# Multi-stage build for FIX Log Comparison Application

# Build stage
FROM maven:3.9.5-openjdk-17-slim AS builder

WORKDIR /app

# Copy pom.xml and download dependencies (for better caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src/ ./src/
RUN mvn clean package -DskipTests

# Runtime stage
FROM openjdk:17-jre-slim

# Install necessary packages
RUN apt-get update && \
    apt-get install -y \
    curl \
    wget \
    fontconfig \
    libfreetype6 \
    && rm -rf /var/lib/apt/lists/*

# Create application user
RUN groupadd -r app && useradd -r -g app app

# Set up application directory
WORKDIR /opt/fix-comparison

# Copy JAR file from build stage
COPY --from=builder /app/target/*.jar ./fix-log-comparison.jar

# Copy configuration files
COPY src/main/resources/application.yml ./
COPY src/main/resources/application-docker.yml ./

# Copy scripts
COPY scripts/run-comparison.sh ./
RUN chmod +x run-comparison.sh

# Create necessary directories
RUN mkdir -p logs reports temp && \
    chown -R app:app /opt/fix-comparison

# Switch to application user
USER app

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Environment variables
ENV JAVA_OPTS="-Xmx2g -Xms512m -Djava.awt.headless=true"
ENV SPRING_PROFILES_ACTIVE=docker

# Expose port (if needed for health checks)
EXPOSE 8080

# Default command
ENTRYPOINT ["java"]
CMD ["-jar", "fix-log-comparison.jar"]