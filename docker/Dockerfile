FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/quarkus-app/lib/ /app/lib/
COPY target/quarkus-app/*.jar /app/
COPY target/quarkus-app/app/ /app/app/
COPY target/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8080

ENV JAVA_OPTS="-Xms64m -Xmx256m -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/quarkus-run.jar"]
