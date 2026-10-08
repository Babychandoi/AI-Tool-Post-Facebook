FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,id=marketing-maven,target=/root/.m2 mvn -q dependency:go-offline
COPY src src
RUN --mount=type=cache,id=marketing-maven,target=/root/.m2 mvn -q -DskipTests package
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core curl && rm -rf /var/lib/apt/lists/* && useradd -m app
WORKDIR /app
COPY --from=build /app/target/marketing-0.1.0.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-Djava.awt.headless=true","-XX:MaxRAMPercentage=70","-jar","app.jar"]
