FROM jenkins/jenkins:2.568.1-jdk21@sha256:f4f65e6cd1405cd889b7f5ac33f9d5cdc2a099de6b87fe8a3933b9c5d53d1d02

ARG JENKINS_UPDATE_CENTER=https://raw.githubusercontent.com/lework/jenkins-update-center/master/updates/huawei

ENV JENKINS_UC=${JENKINS_UPDATE_CENTER} \
    JENKINS_PLUGIN_INFO=https://archives.jenkins.io/update-center/current/plugin-versions.json

USER root
RUN mkdir -p /usr/share/jenkins/ref/update-center-rootCAs \
    && curl --fail --location --silent --show-error \
      https://cdn.jsdelivr.net/gh/lework/jenkins-update-center/rootCA/update-center.crt \
      --output /usr/share/jenkins/ref/update-center-rootCAs/update-center.crt \
    && echo 'c049e4b441fb42675b7bc8c19895f85837c955784299fb2671323b1963da8d18  /usr/share/jenkins/ref/update-center-rootCAs/update-center.crt' \
      | sha256sum --check --strict \
    && chown -R jenkins:jenkins /usr/share/jenkins/ref/update-center-rootCAs

USER jenkins
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --verbose --plugin-file /usr/share/jenkins/ref/plugins.txt
