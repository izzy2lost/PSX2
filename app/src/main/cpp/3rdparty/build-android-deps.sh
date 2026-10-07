#!/usr/bin/env bash
# Builds the prebuilt static libraries in 3rdparty/libs/<abi>/ for an Android ABI.
#
#   usage: build-android-deps.sh <abi> [work_dir]
#   e.g.   build-android-deps.sh x86_64
#
# Versions match the original arm64-v8a prebuilts (and the headers in 3rdparty/include):
#   OpenSSL 3.0.7, nghttp2 1.50.0, curl 7.86.0, libpng 1.6.38,
#   libjpeg-turbo 2.1.4, libpcap 1.9.1, shaderc (in-tree source, v2024.2-dev + DEPS revisions)
set -euo pipefail

ABI="${1:?usage: $0 <abi> [work_dir]}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="${2:-$SCRIPT_DIR/../../../../../build/android-deps}/$ABI"
OUT="$SCRIPT_DIR/libs/$ABI"
API=26
JOBS="$(nproc)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_ROOT:-$SDK/ndk/30.0.15729638}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CMAKE_TC="$NDK/build/cmake/android.toolchain.cmake"

case "$ABI" in
	x86_64)    OPENSSL_TARGET=android-x86_64 ;;
	arm64-v8a) OPENSSL_TARGET=android-arm64 ;;
	*) echo "unsupported ABI: $ABI" >&2; exit 1 ;;
esac

mkdir -p "$WORK/src" "$WORK/prefix" "$OUT"
PREFIX="$WORK/prefix"
cd "$WORK/src"

fetch() { # url dir
	[ -d "$2" ] && return
	curl -fsSL "$1" -o "$2.tar.gz"
	mkdir "$2" && tar xzf "$2.tar.gz" -C "$2" --strip-components=1
}

android_cmake() { # src build [args...]
	local src="$1" build="$2"; shift 2
	cmake -S "$src" -B "$build" -G Ninja \
		-DCMAKE_TOOLCHAIN_FILE="$CMAKE_TC" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-$API \
		-DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
		-DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF -DCMAKE_POLICY_VERSION_MINIMUM=3.5 "$@"
}

echo "== OpenSSL"
fetch https://github.com/openssl/openssl/releases/download/openssl-3.0.7/openssl-3.0.7.tar.gz openssl
if [ ! -f "$PREFIX/lib/libssl.a" ]; then
	(cd openssl && PATH="$TOOLCHAIN/bin:$PATH" ANDROID_NDK_ROOT="$NDK" \
		./Configure "$OPENSSL_TARGET" -D__ANDROID_API__=$API no-shared no-tests --prefix="$PREFIX" --libdir=lib \
		&& PATH="$TOOLCHAIN/bin:$PATH" ANDROID_NDK_ROOT="$NDK" make -j"$JOBS" build_libs \
		&& make install_dev)
fi

echo "== nghttp2"
fetch https://github.com/nghttp2/nghttp2/releases/download/v1.50.0/nghttp2-1.50.0.tar.gz nghttp2
android_cmake nghttp2 nghttp2/build-android -DENABLE_LIB_ONLY=ON -DENABLE_STATIC_LIB=ON -DENABLE_SHARED_LIB=OFF
cmake --build nghttp2/build-android -j"$JOBS" --target install

echo "== curl"
fetch https://curl.se/download/curl-7.86.0.tar.gz curl
android_cmake curl curl/build-android -DBUILD_CURL_EXE=OFF -DBUILD_TESTING=OFF \
	-DCURL_USE_OPENSSL=ON -DOPENSSL_INCLUDE_DIR="$PREFIX/include" \
	-DOPENSSL_SSL_LIBRARY="$PREFIX/lib/libssl.a" -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib/libcrypto.a" \
	-DUSE_NGHTTP2=ON -DNGHTTP2_INCLUDE_DIR="$PREFIX/include" -DNGHTTP2_LIBRARY="$PREFIX/lib/libnghttp2.a" \
	-DCURL_ZLIB=ON -DCURL_DISABLE_LDAP=ON -DCURL_USE_LIBSSH2=OFF -DCURL_USE_LIBPSL=OFF
cmake --build curl/build-android -j"$JOBS" --target install

echo "== libpng"
fetch https://download.sourceforge.net/libpng/libpng-1.6.38.tar.gz libpng
android_cmake libpng libpng/build-android -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF -DPNG_EXECUTABLES=OFF
cmake --build libpng/build-android -j"$JOBS" --target install

echo "== libjpeg-turbo"
fetch https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/2.1.4.tar.gz libjpeg-turbo
# WITH_SIMD needs nasm/yasm on x86; keep it off so the build has no extra host deps.
android_cmake libjpeg-turbo libjpeg-turbo/build-android -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_SIMD=OFF -DWITH_TURBOJPEG=OFF
cmake --build libjpeg-turbo/build-android -j"$JOBS" --target install

echo "== libpcap"
fetch https://www.tcpdump.org/release/libpcap-1.9.1.tar.gz libpcap
# CMake 4 no longer accepts OLD for these policies.
sed -i -E 's/cmake_policy\(SET (CMP[0-9]+) OLD\)/cmake_policy(SET \1 NEW)/' libpcap/CMakeLists.txt
android_cmake libpcap libpcap/build-android -DDISABLE_DBUS=ON -DDISABLE_RDMA=ON -DDISABLE_BLUETOOTH=ON \
	-DDISABLE_DPDK=ON -DBUILD_WITH_LIBNL=OFF -DENABLE_REMOTE=OFF
cmake --build libpcap/build-android -j"$JOBS" --target pcap_static
cp libpcap/build-android/libpcap.a "$PREFIX/lib/libpcap.a"

echo "== shaderc"
# Build from a patched copy: shaderc sets GLSLANG_ENABLE_INSTALL to a generator
# expression string, which if() treats as true, and glslang's install export then fails.
SHADERC_SRC="$PWD/shaderc-src"
rm -rf "$SHADERC_SRC" && mkdir "$SHADERC_SRC"
tar -C "$SCRIPT_DIR/shaderc" --exclude=./libs -cf - . | tar -C "$SHADERC_SRC" -xf -
sed -i 's/set(GLSLANG_ENABLE_INSTALL \$<NOT:\${SKIP_GLSLANG_INSTALL}>)/set(GLSLANG_ENABLE_INSTALL OFF)/' \
	"$SHADERC_SRC/third_party/CMakeLists.txt"
dep() { # name url revision
	[ -d "shaderc-deps/$1" ] && return
	git init -q "shaderc-deps/$1"
	git -C "shaderc-deps/$1" fetch -q --depth 1 "$2" "$3"
	git -C "shaderc-deps/$1" checkout -q FETCH_HEAD
}
rev() { grep -oP "'$1_revision': *'\K[0-9a-f]+" "$SHADERC_SRC/DEPS"; }
dep glslang https://github.com/KhronosGroup/glslang.git "$(rev glslang)"
dep spirv-tools https://github.com/KhronosGroup/SPIRV-Tools.git "$(rev spirv_tools)"
dep spirv-headers https://github.com/KhronosGroup/SPIRV-Headers.git "$(rev spirv_headers)"
android_cmake "$SHADERC_SRC" shaderc-build \
	-DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON -DSHADERC_SKIP_COPYRIGHT_CHECK=ON -DSHADERC_SKIP_INSTALL=ON \
	-DSHADERC_GLSLANG_DIR="$PWD/shaderc-deps/glslang" \
	-DSHADERC_SPIRV_TOOLS_DIR="$PWD/shaderc-deps/spirv-tools" \
	-DSHADERC_SPIRV_HEADERS_DIR="$PWD/shaderc-deps/spirv-headers" \
	-DSPIRV_SKIP_EXECUTABLES=ON -DSPIRV_SKIP_TESTS=ON -DENABLE_GLSLANG_BINARIES=OFF -DGLSLANG_TESTS=OFF \
	-DSKIP_GLSLANG_INSTALL=ON -DGLSLANG_ENABLE_INSTALL=OFF -DSKIP_SPIRV_TOOLS_INSTALL=ON
cmake --build shaderc-build -j"$JOBS" --target shaderc_combined
cp shaderc-build/libshaderc/libshaderc_combined.a "$PREFIX/lib/libshaderc.a"

echo "== collect"
for lib in libcrypto libssl libnghttp2 libcurl libpng16 libjpeg libpcap libshaderc; do
	cp "$PREFIX/lib/$lib.a" "$OUT/$lib.a"
	# The NDK toolchain adds -g; drop debug info so the archives stay small enough for git.
	"$TOOLCHAIN/bin/llvm-strip" --strip-debug "$OUT/$lib.a"
done
ls -la "$OUT"
