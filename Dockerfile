FROM debian:jessie
MAINTAINER "Jonathan Jacobs <jj@fusionapp.com>"
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -qy openjdk-7-jre-headless imagemagick && rm -rf /var/lib/apt/lists/*
COPY docker/fop.xconf /root/.config/documint/fop.xconf
COPY fonts /appenv/fonts/
COPY ["target/uberjar/documint-0.1.0-SNAPSHOT-standalone.jar", "/srv/clj-documint/clj-documint.jar"]
WORKDIR /db
ENTRYPOINT ["/usr/bin/java", "-jar", "/srv/clj-documint/clj-documint.jar"]
ENV DOCUMINT_PORT=3000
EXPOSE 3000
