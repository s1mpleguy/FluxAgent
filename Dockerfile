FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
ENV JAVA_OPTS=""
COPY --from=build /workspace/target/flux-agent-0.1.0.jar /app/flux-agent.jar
COPY skills /app/skills
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/flux-agent.jar \"$@\"", "--"]
