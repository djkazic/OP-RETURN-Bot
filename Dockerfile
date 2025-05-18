FROM eclipse-temurin:11-jdk AS builder

# Install sbt
RUN apt-get update && \
    apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99e82a75642ac823" | apt-key add - && \
    apt-get update && \
    apt-get install -y sbt

WORKDIR /app
COPY . .

# Default command
CMD ["sbt", "run"]
