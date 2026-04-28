FROM thyrlian/android-sdk:latest

USER root

RUN apt-get update && apt-get install -y \
    curl \
    wget \
    unzip \
    python3 \
    python3-pip \
    jq \
    zip \
    git \
    && rm -rf /var/lib/apt/lists/*

# Java 21 (Temurin) — Jenkins LTS requires Java 21 for remoting
RUN wget -qO /tmp/jdk21.tar.gz \
    "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.6%2B7/OpenJDK21U-jdk_x64_linux_hotspot_21.0.6_7.tar.gz" \
    && mkdir -p /opt/java/21 \
    && tar -xzf /tmp/jdk21.tar.gz -C /opt/java/21 --strip-components=1 \
    && rm /tmp/jdk21.tar.gz
ENV JAVA_HOME=/opt/java/21
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Semgrep
RUN pip3 install --no-cache-dir --break-system-packages semgrep

# OWASP Dependency-Check 9.0.9
RUN wget -q \
    "https://github.com/jeremylong/DependencyCheck/releases/download/v9.0.9/dependency-check-9.0.9-release.zip" \
    -O /tmp/dependency-check.zip \
    && unzip /tmp/dependency-check.zip -d /opt/ \
    && rm /tmp/dependency-check.zip \
    && chmod +x /opt/dependency-check/bin/dependency-check.sh
ENV PATH="/opt/dependency-check/bin:$PATH"

# Jenkins JNLP agent jar (for Option C)
ARG REMOTING_VERSION=3248.3250.v3277a_8e88c9b_
RUN mkdir -p /usr/share/jenkins \
    && curl -sL \
    "https://repo.jenkins-ci.org/releases/org/jenkins-ci/main/remoting/${REMOTING_VERSION}/remoting-${REMOTING_VERSION}.jar" \
    -o /usr/share/jenkins/agent.jar \
    && chmod 644 /usr/share/jenkins/agent.jar

COPY semgrep-to-html.py /opt/semgrep-to-html.py
COPY shared/llm-analysis.sh /opt/llm-analysis.sh
COPY shared/check-licenses.sh /opt/check-licenses.sh
RUN chmod +x /opt/llm-analysis.sh /opt/check-licenses.sh

RUN useradd -d /home/jenkins -m -s /bin/bash jenkins 2>/dev/null || true \
    && mkdir -p /home/jenkins/agent \
    && printf '[safe]\n\tdirectory = *\n' > /home/jenkins/.gitconfig \
    && chown -R jenkins:jenkins /home/jenkins

USER jenkins
WORKDIR /home/jenkins/agent

# Option C entrypoint: override with JNLP agent command when used as static agent
CMD ["bash"]
