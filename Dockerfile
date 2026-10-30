FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY PaymentApp.java .
RUN javac PaymentApp.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/*.class .
EXPOSE 8080
CMD ["java", "PaymentApp"]
