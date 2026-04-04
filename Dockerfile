# Używamy lekkiego obrazu Javy 17
FROM eclipse-temurin:17-jre-alpine

# Kopiujemy zbudowany plik JAR do kontenera
# GitHub Actions zbuduje go pod nazwą z pom.xml
COPY target/*.jar app.jar

# Komenda startowa
ENTRYPOINT ["java", "-jar", "/app.jar"]