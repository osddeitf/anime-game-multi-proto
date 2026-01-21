FROM gradle:9.2.1-jdk17-ubi

LABEL authors="osddeitf"

ENV KONAN_DATA_DIR=/cache/.konan
ENV GRADLE_USER_HOME=/cache/.gradle

WORKDIR /libs
COPY . .

RUN --mount=type=cache,target=/cache/.gradle \
    --mount=type=cache,target=/cache/.konan \
    gradle --info \
    gi:publishJvmPublicationToMavenLocal \
    runtime:publishJvmPublicationToMavenLocal \
    clean
