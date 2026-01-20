FROM mcr.microsoft.com/devcontainers/java:1-21-bullseye

# Install Git explicitly via apt-get
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends git
