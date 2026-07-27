########################################################################
# SageTV Docker Build — Multi-stage
#   Stage 1 (builder): compiles Java + native code
#   Stage 2 (runtime): slim image with only what's needed to run
########################################################################
FROM ubuntu:24.04 AS builder

ARG DEBIAN_FRONTEND=noninteractive

# ---- build-time packages ----
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-21-jdk-headless \
    git \
    gcc \
    g++ \
    make \
    autoconf \
    automake \
    libtool \
    nasm \
    yasm \
    pkg-config \
    zlib1g-dev \
    libasound2-dev \
    libx11-dev \
    libxv-dev \
    libfreetype-dev \
    dpkg-dev \
    ca-certificates \
    libx264-dev \
    libxvidcore-dev \
    libfaad-dev \
    libfaac-dev \
    libfdk-aac-dev \
    libx265-dev \
    libmp3lame-dev \
    python3 \
    wget \
    xz-utils \
    libargtable2-dev \
    libavformat-dev \
    libavutil-dev \
    libavcodec-dev \
    libswscale-dev \
    libswresample-dev \
    `# CUDA toolkit for --enable-cuda-nvcc + --enable-libnpp (scale_npp filter)` \
    nvidia-cuda-toolkit \
    libnpp-dev \
    `# VAAPI for --enable-vaapi (AMD/Intel GPU encode + scale_vaapi filter)` \
    libva-dev \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV JDK_HOME=${JAVA_HOME}
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /src
COPY . /src

# Make gradlew and all build scripts executable
RUN chmod +x gradlew build/*.sh third_party/mplayer/configure \
    || true

# build.gradle calls "git rev-list HEAD --count" at configuration time
# to determine the build number. Since .git is excluded from the Docker
# context, we init a temporary repo so the command succeeds.
RUN git config --global user.email "build@docker" && git config --global user.name "Docker Build" && git init && git add -A && git commit -m "docker build" --quiet

# ---- 1. Build Sage.jar (Java 21) ----
RUN ./gradlew sageJar -x updateBuildNumber -x test --no-daemon

# ---- 2. Build MiniClient.jar ----
RUN ./gradlew miniclientJar -x updateBuildNumber -x test --no-daemon || true

# ---- 3. Build native shared libraries ----
# buildso.sh exits on first failure (e.g. FirewireCapture), which prevents
# later essential libs (ImageLoader) from building. Build critical parts manually.
WORKDIR /src/build
RUN mkdir -p so/irtunerplugins

# -- 3a. Essential: libSage.so --
RUN make -C ../native/so/SageLinux && cp ../native/so/SageLinux/*.so so/ \
    || echo "WARN: libSage build failed"

# -- 3b. Non-essential capture device drivers (tolerate failures) --
RUN make -C ../native/so/IVTVCapture && cp ../native/so/IVTVCapture/*.so so/ || true
RUN make -C ../third_party/jtux/native/so && cp ../third_party/jtux/native/so/*.so so/ || true
RUN make -C ../native/so/PVR150Input && cp ../native/so/PVR150Input/*.so so/ || true
RUN make -C ../native/so/DVBCapture2.0 && cp ../native/so/DVBCapture2.0/*.so so/ || true
RUN make -C ../native/so/FirewireCapture && cp ../native/so/FirewireCapture/*.so so/ || true
RUN make -C ../native/so/MPEGParser2.0 clean \
    && make -C ../native/so/MPEGParser2.0 JDK_HOME=${JDK_HOME} \
    && cp ../native/so/MPEGParser2.0/libMPEGParser.so so/ \
    && cp ../native/so/MPEGParser2.0/libNativeCore.so so/ \
    && test -s so/libMPEGParser.so \
    && test -s so/libNativeCore.so
RUN make -C ../native/so/HDHomeRun2.0 && cp ../native/so/HDHomeRun2.0/*.so so/ || true
RUN make -C ../native/dll/JavaRemuxer2 && cp ../native/dll/JavaRemuxer2/*.so so/ || true
RUN make -C ../native/so/PVR150Tuning && cp ../native/so/PVR150Tuning/*.so so/irtunerplugins/ || true
RUN make -C ../native/so/DirecTVSerialControl && cp ../native/so/DirecTVSerialControl/*.so so/irtunerplugins/ || true
RUN make -C ../native/so/FirewireTuning && cp ../native/so/FirewireTuning/*.so so/irtunerplugins/ || true

# -- 3c. Essential: libImageLoader.so + its codec dependencies --
RUN make -C ../third_party/swscale && cp ../third_party/swscale/*.so so/ \
    || echo "WARN: swscale build failed"
RUN cd ../third_party/codecs/giflib && ./configure --with-pic && make \
    || echo "WARN: giflib build failed"
RUN cd ../third_party/codecs/jpeg-6b && ./configure CFLAGS=-fPIC && make \
    || echo "WARN: libjpeg build failed"
RUN cd ../third_party/codecs/libpng && ./configure --with-pic && make \
    || echo "WARN: libpng build failed"
RUN cd ../third_party/codecs/tiff && ./configure --with-pic && make \
    || echo "WARN: libtiff build failed"
RUN make -C ../third_party/SageTV-LGPL/imageload && cp ../third_party/SageTV-LGPL/imageload/*.so so/ \
    || echo "WARN: ImageLoader build failed"

# -- 3d. Essential: Freetype --
RUN make -C ../native/crosslibs/Freetype && cp ../native/crosslibs/Freetype/*.so so/ || true

# ---- 4. Build the unified SageTV FFmpeg binary ----
# One binary with: all four SageTV custom flags (-stdinctrl, -activefile,
# -dumpmetadata, -brokendts) + AC-4 decode + NVENC + libx264/x265/fdk-aac/xvid.
# Replaces the old dual build-modern-ffmpeg.sh + build-ac4-ffmpeg.sh setup
# and removes the need for ffmpeg-wrapper.sh. See docs/FFMPEG_UNIFICATION_PLAN.md.
RUN bash /src/docker/build-sagetv-ffmpeg.sh \
    && echo "Unified SageTV FFmpeg built successfully"

# ---- 4b. Build Comskip commercial detector ----
# If third_party/comskip is populated (git submodule present) build from source;
# otherwise the runtime stage installs the Ubuntu package as a fallback so the
# image always has a working comskip binary.
WORKDIR /src/third_party/comskip
RUN if [ -f autogen.sh ]; then \
      ./autogen.sh \
        && ./configure \
        && make \
        && cp comskip /src/build/elf/comskip \
        && echo "Comskip built from source"; \
    else \
      echo "WARN: third_party/comskip empty (submodule missing) - runtime apt fallback will be used"; \
    fi \
    && mkdir -p /src/build/elf \
    && touch /src/build/elf/comskip.marker
WORKDIR /src/build

# Copy jpegtran if available
RUN cp /src/third_party/codecs/jpeg-6b/jpegtran /src/build/elf/jpegtran 2>/dev/null || true

# ---- 5. Assemble server release directory ----
RUN ./copyserverfiles.sh || echo "WARN: copyserverfiles had errors"

# ---- 6. Copy any .so files that copyserverfiles may have missed ----
RUN cp -n so/*.so serverrelease/ 2>/dev/null || true \
    && cp -rn so/irtunerplugins/*.so serverrelease/irtunerplugins/ 2>/dev/null || true

# Ensure the MPEG parser JNI libs in the release are freshly built from source.
RUN install -m 755 so/libMPEGParser.so serverrelease/libMPEGParser.so \
    && install -m 755 so/libNativeCore.so serverrelease/libNativeCore.so \
    && strings serverrelease/libMPEGParser.so | grep -E 'HEVC|AC4' >/tmp/mpegparser-codec-tags.txt

# ---- 7. Remove old Lucene 3.6 JAR (conflicts with 4.10.4 on classpath) ----
RUN rm -f serverrelease/JARs/lucene-core-3.6.0.jar

########################################################################
# Stage 2 — Runtime image
########################################################################
FROM ubuntu:24.04 AS runtime

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-21-jre-headless \
    tini \
    libasound2t64 \
    libfreetype6 \
    libharfbuzz0b \
    fontconfig \
    fonts-dejavu-core \
    libx11-6 \
    ca-certificates \
    ffmpeg \
    libx264-164 \
    libxvidcore4 \
    libfaad2 \
    libfaac0 \
    libfdk-aac2 \
    libx265-199 \
    libmp3lame0 \
    `# VAAPI runtime libs (AMD/Intel GPU accel — no-op if no DRI device present)` \
    libva2 \
    libva-drm2 \
    comskip \
    ccextractor \
    nocache \
    hdhomerun-config \
    `# === GLVND client dispatch stack (REQUIRED for realesrgan-ncnn-vulkan) ===` \
    `# NVIDIA's libGLX_nvidia.so Vulkan ICD probes for the full GLVND EGL/GL` \
    `# client libs at vk_icdGetInstanceProcAddr and self-disables (returns NULL,` \
    `# VK_ERROR_INCOMPATIBLE_DRIVER / vkCreateInstance -9) if they are absent —` \
    `# BEFORE any device access. libegl1 (libEGL.so.1) and libopengl0` \
    `# (libOpenGL.so.0) are the two that a minimal image omits; libglx0 /` \
    `# libglvnd0 / libgl1 pull in the rest of the dispatch layer. Without these,` \
    `# nvidia-smi works but vulkaninfo fails inside the container even though it` \
    `# works on the host. See NVIDIA/nvidia-container-toolkit issue #191.` \
    libglvnd0 \
    libgl1 \
    libglx0 \
    libegl1 \
    libopengl0 \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Create sagetv user — uid/gid 1000 to match the host sagetv account on
# our deploy targets, so bind-mounted host files (Sage.properties, Wiz.bin,
# /var/media/*, /mnt/*/sagetv*) are owned by the same numeric uid inside
# and outside the container. Override with --build-arg if your host uses
# different ids.
#
# ubuntu:24.04 ships a default `ubuntu` user at uid 1000; remove it first
# so the requested SAGETV_UID is free.
ARG SAGETV_UID=1000
ARG SAGETV_GID=1000
RUN if id ubuntu >/dev/null 2>&1; then userdel -r ubuntu 2>/dev/null || userdel ubuntu; fi \
    && if getent group ubuntu >/dev/null 2>&1; then groupdel ubuntu 2>/dev/null || true; fi \
    && groupadd -g ${SAGETV_GID} sagetv \
    && useradd -u ${SAGETV_UID} -g ${SAGETV_GID} -d /opt/sagetv -s /bin/bash sagetv

# Copy the assembled server release from builder
COPY --from=builder /src/build/serverrelease /opt/sagetv/server

# If native build failed, at minimum copy the Sage.jar so the container is useful
COPY --from=builder /src/build/release/Sage.jar /opt/sagetv/server/Sage.jar

# If comskip was built from source in the builder stage, pull it in too;
# otherwise the apt-installed comskip from the runtime stage is used.
COPY --from=builder /src/build/elf/comskip.marker /tmp/.comskip-marker
COPY --from=builder /src/build/elf/ /tmp/builder-elf/

# AI-upscale wrapper (phase-1 of the chained upscale transcode job). This is a
# source script under bin/, NOT part of the legacy serverrelease assembly, so
# copy it in explicitly. The realesrgan-ncnn-vulkan binary + models are
# bind-mounted at /opt/realesrgan:ro by the deploy runner; the wrapper defaults
# to that path and SageTV passes transcoder/ai_upscale_binary at invocation.
COPY bin/sage-ai-upscale.sh /opt/sagetv/server/bin/sage-ai-upscale.sh

# State-managed supervisor entrypoint. Folding this into the image means a
# single reproducible `docker build` produces the COMPLETE production image
# (state supervisor + unified ffmpeg) with no post-build `docker commit` step.
# The script is PII-free and env-driven: per-install identity/state files
# (Sage.properties, Wiz.bin, SageTVLocator keys, sdauth, plugins, clients/...)
# are provided at RUNTIME via mounted state (STATE_DIR), never baked here.
# With no STATE_DIR/CONTAINER_NAME set it falls back to running java directly.
COPY --chmod=0755 docker/entrypoint-state.sh /usr/local/bin/entrypoint-state.sh

WORKDIR /opt/sagetv/server

# FFmpeg setup — install the unified SageTV-patched + AC-4-capable binary at
# /opt/sagetv/server/{ffmpeg,ffprobe}. Built in stage 1 by build-sagetv-ffmpeg.sh.
# Stock /usr/bin/ffmpeg (apt) is kept for comskip and unrelated tools.
RUN if [ -x /tmp/builder-elf/sagetv-ffmpeg ]; then \
         echo "Installing unified SageTV FFmpeg at /opt/sagetv/server/{ffmpeg,ffprobe}"; \
         install -m 755 /tmp/builder-elf/sagetv-ffmpeg  /opt/sagetv/server/ffmpeg; \
         install -m 755 /tmp/builder-elf/sagetv-ffprobe /opt/sagetv/server/ffprobe; \
       else \
         echo "ERROR: sagetv-ffmpeg not built - transcoding paths will be broken" >&2; \
         exit 1; \
       fi \
    && mkdir -p /var/media/videos /var/media/pictures /var/media/music \
    /opt/sagetv/server/logs /opt/sagetv/comskip \
    && if [ -x /tmp/builder-elf/comskip ]; then \
         echo "Using comskip built from source in builder stage"; \
         cp /tmp/builder-elf/comskip /opt/sagetv/comskip/comskip; \
       else \
         echo "Using apt-installed comskip"; \
         ln -sf /usr/bin/comskip /opt/sagetv/comskip/comskip; \
       fi \
    && cp /opt/sagetv/server/comskip_profiles/comskip_base.ini /opt/sagetv/comskip/comskip.ini 2>/dev/null || true \
    # Drop comskip.ini in every location comskip auto-discovers so Sage can
    # invoke comskip without --ini= argument. Comskip searches: CWD, $HOME
    # (.comskip.ini), and each $PATH entry for "comskip.ini". Sage runs
    # comskip from CWD=/opt/sagetv/server with HOME=/opt/sagetv as user sagetv.
    && if [ -f /opt/sagetv/comskip/comskip.ini ]; then \
         install -m 644 /opt/sagetv/comskip/comskip.ini /opt/sagetv/server/comskip.ini; \
         install -m 644 /opt/sagetv/comskip/comskip.ini /opt/sagetv/.comskip.ini; \
         install -m 644 /opt/sagetv/comskip/comskip.ini /usr/bin/comskip.ini; \
         install -m 644 /opt/sagetv/comskip/comskip.ini /etc/comskip.ini; \
       fi \
    && rm -rf /tmp/builder-elf /tmp/.comskip-marker \
    && chmod -R 755 /opt/sagetv \
    && chown -R sagetv:sagetv /opt/sagetv /var/media

# Expose SageTV ports:
#   8080  - Web UI (Jetty)
#   7818  - SageTV client connections
#   31099 - Placeshifter
EXPOSE 8080 7818 31099

# No VOLUME declarations — all persistence handled via explicit bind mounts
# at container creation time (see docker-compose.yml)

ENV LD_LIBRARY_PATH=/opt/sagetv/server:/opt/sagetv/server/lib:/opt/sagetv/server/irtunerplugins

# Run as sagetv user
USER sagetv

# Use tini as PID 1 for proper signal handling and zombie reaping, and hand
# off to the state-managed supervisor which spawns/respawns Sage in-place and
# stages state from STATE_DIR (or runs java directly in standalone mode). The
# java command below is passed through to the supervisor as "$@".
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint-state.sh"]

# Start SageTV in headless server mode
# --add-opens flags required for bundled GSON library which uses sun.misc.Unsafe
# and reflection on java.lang.reflect internals (Java 21 blocks these by default)
CMD ["java", \
     "-Djava.awt.headless=true", \
     "--add-opens", "jdk.unsupported/sun.misc=ALL-UNNAMED", \
     "--add-opens", "java.base/java.lang=ALL-UNNAMED", \
     "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED", \
     "--add-opens", "java.base/java.io=ALL-UNNAMED", \
     "-Xms768m", \
     "-Xmx1536m", \
     "-XX:+UseG1GC", \
     "-XX:+UseStringDeduplication", \
     "-XX:+UseAdaptiveSizePolicy", \
     "-XX:MaxGCPauseMillis=50", \
     "-XX:GCTimeRatio=19", \
     "-XX:+ParallelRefProcEnabled", \
     "-XX:ErrorFile=/opt/sagetv/server/logs/hs_err_%p.log", \
     "-XX:+HeapDumpOnOutOfMemoryError", \
     "-XX:HeapDumpPath=/opt/sagetv/server/logs/", \
     "-cp", "Sage.jar:JARs/*", \
     "sage.Sage", "0", "0", "x", "sagetv Sage.properties"]
