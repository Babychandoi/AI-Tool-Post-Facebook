FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core curl && rm -rf /var/lib/apt/lists/* && useradd -m app
WORKDIR /app
COPY artifacts/marketing-0.1.0.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-Djava.awt.headless=true","-XX:MaxRAMPercentage=70","-jar","app.jar"]
