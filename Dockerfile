FROM debian:jessie
MAINTAINER "Jonathan Jacobs <jonathan@jsphere.com>"
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -qy openjdk-7-jre-headless
COPY ["target/uberjar/clj-documint-0.1.0-SNAPSHOT-standalone.jar", "/srv/clj-documint/clj-documint.jar"]
WORKDIR /srv/clj-documint
ENTRYPOINT ["/usr/bin/java", "-jar", "/srv/clj-documint/clj-documint.jar"]
EXPOSE 8880
EXPOSE 8881
