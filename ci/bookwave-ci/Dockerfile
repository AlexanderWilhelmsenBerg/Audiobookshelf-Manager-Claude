FROM node:22-bookworm

ARG ANDROID_CMDLINE_TOOLS_BUILD=15859902
ARG ANDROID_CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG GITLEAKS_VERSION=8.30.1
ARG GITLEAKS_LINUX_X64_SHA256=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
ARG SOURCE_REVISION=unknown

LABEL org.opencontainers.image.source="https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude"
LABEL org.opencontainers.image.title="BookWave CI"
LABEL org.opencontainers.image.description="Pinned Android/JDK toolchain for BookWave CI"
LABEL org.opencontainers.image.revision="${SOURCE_REVISION}"

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/lib/jvm/java-17-openjdk-amd64/bin:/usr/local/bin:${PATH}

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      ca-certificates curl git jq openjdk-17-jdk-headless python3 tar unzip; \
    rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    mkdir -p /opt/android-sdk/cmdline-tools; \
    tmp="$(mktemp -d)"; \
    curl --fail --location --silent --show-error \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_BUILD}_latest.zip" \
      --output "$tmp/commandlinetools.zip"; \
    printf '%s  %s\n' "$ANDROID_CMDLINE_TOOLS_SHA256" "$tmp/commandlinetools.zip" | sha256sum --check -; \
    unzip -q "$tmp/commandlinetools.zip" -d "$tmp"; \
    mv "$tmp/cmdline-tools" /opt/android-sdk/cmdline-tools/latest; \
    printf '%s\n' "$ANDROID_CMDLINE_TOOLS_BUILD" > /opt/android-sdk/cmdline-tools/latest/.bookwave-commandline-tools-build; \
    yes | sdkmanager --sdk_root=/opt/android-sdk --licenses >/dev/null 2>&1 || true; \
    sdkmanager --sdk_root=/opt/android-sdk \
      "platform-tools" \
      "platforms;android-36" \
      "build-tools;36.0.0"; \
    rm -rf "$tmp"; \
    chmod -R a+rwX /opt/android-sdk

RUN set -eux; \
    tmp="$(mktemp -d)"; \
    curl --fail --location --silent --show-error \
      "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz" \
      --output "$tmp/gitleaks.tar.gz"; \
    printf '%s  %s\n' "$GITLEAKS_LINUX_X64_SHA256" "$tmp/gitleaks.tar.gz" | sha256sum --check -; \
    tar -xzf "$tmp/gitleaks.tar.gz" -C "$tmp" gitleaks; \
    install -m 0755 "$tmp/gitleaks" /usr/local/bin/gitleaks; \
    rm -rf "$tmp"

COPY ci/bookwave-ci/verify.sh /usr/local/bin/bookwave-ci-verify
RUN chmod 0755 /usr/local/bin/bookwave-ci-verify && /usr/local/bin/bookwave-ci-verify

WORKDIR /workspace
