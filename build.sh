#!/usr/bin/bash
export GRADLE_OPTS=-Dorg.gradle.daemon=false
export JAVA_HOME=$JAVA_HOME_17_X64

export CGO_ENABLED=0
export GOOS=android
export GOARCH=arm64
export GOARM64="v9.0,lse,crypto"

# Setup Go
if [ ! -d go/bin ]; then
    GO_LATEST=$(curl -fsSL "https://go.dev/dl/?mode=json" | jq -r ".[0].version")
    curl -fsSL "https://go.dev/dl/${GO_LATEST}.linux-amd64.tar.gz" | tar -xzf-
fi
export PATH="$(pwd)/go/bin:$PATH"

git submodule update --init --recursive

# Clone Xray-core
XRAY_TAG=$(curl -fsSL "https://api.github.com/repos/XTLS/Xray-core/releases/latest" | jq -r ".tag_name")
git clone -b $XRAY_TAG --depth 1 --single-branch https://github.com/XTLS/Xray-core
pushd Xray-core
COMMID=$(git rev-parse HEAD | cut -c 1-7)
GCFLAGS="-l=4 -B"
LDFLAGS="-X github.com/xtls/xray-core/core.build=$COMMID -s -w -buildid="

# Build Xray-core
mkdir -p ../app/src/main/jniLibs/arm64-v8a
go build -o ../app/src/main/jniLibs/arm64-v8a/libxray.so -trimpath -buildvcs=false -gcflags=all="$GCFLAGS" -ldflags="$LDFLAGS" ./main
popd

# Install custom lwIP config
install -v lwipopts.h app/src/main/jni/hev-socks5-tunnel/third-part/lwip/src/ports/include/lwipopts.h

# Install NDK wrapper to latest NDK toolchain
install -v -m 0755 ndk-wrapper.py $ANDROID_NDK_LATEST_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/ndk-wrapper.py
pushd $ANDROID_NDK_LATEST_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
if [ ! -e clang_ ]; then
    mv clang clang_
    ln -sf ndk-wrapper.py clang
fi
if [ ! -e clang++_ ]; then
    if [ -L clang++ ]; then
        rm -f clang++
        ln -sf clang_ clang++_
    else
        mv clang++ clang++_
    fi
    ln -sf ndk-wrapper.py clang++
fi
popd

# Link selected NDK to latest
source version.properties
pushd $(dirname $ANDROID_NDK_LATEST_HOME)
[ -d $NDK_VERSION ] && sudo rm -rf $NDK_VERSION
ln -sf $(basename $ANDROID_NDK_LATEST_HOME) $NDK_VERSION
popd

export NDK_WRAPPER_APPEND="-mcpu=cortex-x3+crypto+sha3+nosve -mtune=cortex-a510"
./gradlew assembleRelease
