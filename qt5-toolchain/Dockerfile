FROM ubuntu:22.04

ARG NDK_VERSION=22.1.7171670
ARG SDK_PLATFORM=android-33
ARG SDK_BUILD_TOOLS=30.0.3
ARG MIN_NDK_PLATFORM=android-21
ARG QT_TAG=v5.15.5-lts-lgpl
ARG CRAFTMASTER_REV=51e94ec2b8610b2c2c39c3c6edaa38863da4743d

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_ROOT=/opt/android-sdk/ndk/${NDK_VERSION}
ENV ANDROID_NDK=/opt/android-sdk/ndk/${NDK_VERSION}
ENV ANDROID_NDK_HOST=linux-x86_64
ENV ANDROID_NDK_PLATFORM=${MIN_NDK_PLATFORM}
ENV ANDROID_API_VERSION=${SDK_PLATFORM}
ENV ANDROID_BUILD_TOOLS_REVISION=${SDK_BUILD_TOOLS}
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV PATH=/opt/cmake/bin:/opt/android-sdk/cmdline-tools/tools/bin:/opt/android-sdk/tools:/opt/android-sdk/platform-tools:${PATH}
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8
ENV PERSIST=1

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      autoconf automake autopoint autotools-dev bison build-essential \
      ca-certificates curl flex gettext git gperf libtool locales make nasm \
      ninja-build openjdk-11-jdk openssh-client p7zip-full pkg-config \
      python3 python3-distutils python3-future python3-lxml python3-paramiko \
      python3-pip python3-requests python3-venv python3-xdg python3-yaml \
      shared-mime-info sudo unzip xz-utils zstd \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/* \
 && locale-gen en_US.UTF-8 \
 && update-alternatives --install /usr/bin/python python /usr/bin/python3 10

RUN groupadd -r user \
 && useradd --create-home --gid user user \
 && echo 'user ALL=NOPASSWD: ALL' > /etc/sudoers.d/user \
 && chmod a+w /opt

USER user
WORKDIR /home/user

# Android SDK/NDK versions match KDE's last Qt5 Android image.
RUN curl -fL --retry 8 --retry-all-errors \
      -o /tmp/sdk-tools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-6609375_latest.zip \
 && mkdir -p /opt/android-sdk/cmdline-tools \
 && unzip -q /tmp/sdk-tools.zip -d /opt/android-sdk/cmdline-tools \
 && rm /tmp/sdk-tools.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager --verbose \
      "platforms;${SDK_PLATFORM}" \
      "build-tools;${SDK_BUILD_TOOLS}" \
      "ndk;${NDK_VERSION}" \
      "platform-tools"

# KDE's old image pinned CMake 3.23.3 for the KF5 Android toolchain.
RUN mkdir -p /opt/cmake \
 && curl -fL --retry 8 --retry-all-errors \
      -o /tmp/cmake.sh \
      https://github.com/Kitware/CMake/releases/download/v3.23.3/cmake-3.23.3-linux-x86_64.sh \
 && bash /tmp/cmake.sh --skip-license --prefix=/opt/cmake --exclude-subdir \
 && rm /tmp/cmake.sh

# Native host tools are required while cross compiling Android target packages.
RUN git clone --depth=1 --branch kf5 https://invent.kde.org/frameworks/extra-cmake-modules.git \
 && cmake -S extra-cmake-modules -B ecm-build -DCMAKE_INSTALL_PREFIX=/opt/nativetooling \
 && cmake --build ecm-build --target install --parallel 2 \
 && rm -rf extra-cmake-modules ecm-build

RUN git clone --depth=1 --branch ${QT_TAG} https://invent.kde.org/qt/qt/qtbase.git \
 && cd qtbase \
 && ./configure -prefix /opt/nativetooling -opensource -confirm-license \
      -no-gui -release -optimize-size -nomake tests -nomake examples \
      -no-feature-concurrent \
 && make -j2 \
 && make install \
 && cd .. \
 && rm -rf qtbase

RUN git clone --depth=1 --branch ${QT_TAG} https://invent.kde.org/qt/qt/qtdeclarative.git \
 && cd qtdeclarative \
 && QMAKESPEC=linux-g++ /opt/nativetooling/bin/qmake . \
 && make -j2 \
 && make install \
 && cd .. \
 && rm -rf qtdeclarative

RUN git clone --depth=1 --branch kf5 https://invent.kde.org/frameworks/kconfig.git \
 && rm -rf kconfig/poqm \
 && cmake -S kconfig -B kconfig-build \
      -DCMAKE_PREFIX_PATH=/opt/nativetooling \
      -DCMAKE_INSTALL_PREFIX=/opt/nativetooling \
      -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF -DKCONFIG_USE_GUI=OFF \
 && cmake --build kconfig-build --target install --parallel 2 \
 && rm -rf kconfig kconfig-build

RUN git clone --depth=1 --branch kf5 https://invent.kde.org/frameworks/kcoreaddons.git \
 && rm -rf kcoreaddons/poqm \
 && cmake -S kcoreaddons -B kcoreaddons-build \
      -DCMAKE_PREFIX_PATH=/opt/nativetooling \
      -DCMAKE_INSTALL_PREFIX=/opt/nativetooling \
      -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF \
 && cmake --build kcoreaddons-build --target install --parallel 2 \
 && rm -rf kcoreaddons kcoreaddons-build

COPY --chown=user:user CraftConfig.ini /home/user/CraftConfig.ini

# Bootstrap the retired but still-accessible Qt5-LTS Craft branches.
RUN git clone https://invent.kde.org/packaging/craftmaster.git \
 && git -C craftmaster checkout ${CRAFTMASTER_REV} \
 && python3 craftmaster/CraftMaster.py \
      --config /home/user/CraftConfig.ini \
      --target android-arm64-clang --setup \
 && python3 craftmaster/CraftMaster.py \
      --config /home/user/CraftConfig.ini \
      --target android-arm64-clang -c -i craft

# Fail the image build if Craft drifted away from the intended Qt5 stack.
RUN test -f /home/user/android-arm64-clang/craft/craftenv.sh \
 && git -C /home/user/android-arm64-clang/craft rev-parse HEAD \
 && /opt/nativetooling/bin/qmake -v

WORKDIR /workspace
