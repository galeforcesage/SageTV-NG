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
    libmp3lame-dev \
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
RUN ./gradlew sageJar -x updateBuildNumber --no-daemon

# ---- 2. Build MiniClient.jar ----
RUN ./gradlew miniclientJar -x updateBuildNumber --no-daemon || true

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
RUN make -C ../native/so/MPEGParser2.0 && cp ../native/so/MPEGParser2.0/*.so so/ || true
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

# ---- 4. Build patched SageTV FFmpeg using system codec libraries ----
# The bundled codec sources in third_party/codecs/ are too old for Ubuntu 24.04,
# so we link against the system-installed dev packages instead.
RUN cd /src/third_party/ffmpeg \
    && make clean || true \
    && ./configure \
         --disable-ffserver \
         --disable-ffplay \
         --enable-gpl \
         --enable-pthreads \
         --enable-nonfree \
         --enable-libfaac \
         --enable-libx264 \
         --enable-libxvid \
         --enable-libfaad \
         --disable-devices \
         --disable-bzlib \
         --disable-demuxer=msnwc_tcp \
    && make -j$(nproc) \
    && echo "Patched FFmpeg built successfully" \
    || echo "WARN: Patched FFmpeg build failed (will fall back to stock+wrapper)"

# Copy ffmpeg and jpegtran to elf/ for copyserverfiles.sh
RUN mkdir -p /src/build/elf \
    && cp /src/third_party/ffmpeg/ffmpeg /src/build/elf/ffmpeg 2>/dev/null || true \
    && cp /src/third_party/codecs/jpeg-6b/jpegtran /src/build/elf/jpegtran 2>/dev/null || true

# ---- 5. Assemble server release directory ----
RUN ./copyserverfiles.sh || echo "WARN: copyserverfiles had errors"

# ---- 6. Copy any .so files that copyserverfiles may have missed ----
RUN cp -n so/*.so serverrelease/ 2>/dev/null || true \
    && cp -rn so/irtunerplugins/*.so serverrelease/irtunerplugins/ 2>/dev/null || true

# ---- 7. Remove old Lucene 3.6 JAR (conflicts with 4.10.4 on classpath) ----
RUN rm -f serverrelease/JARs/lucene-core-3.6.0.jar

########################################################################
# Stage 2 — Runtime image
########################################################################
FROM ubuntu:24.04 AS runtime

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-21-jre-headless \
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
    libmp3lame0 \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Create sagetv user
RUN groupadd -r sagetv && useradd -r -g sagetv -d /opt/sagetv sagetv

# Copy the assembled server release from builder
COPY --from=builder /src/build/serverrelease /opt/sagetv/server

# If native build failed, at minimum copy the Sage.jar so the container is useful
COPY --from=builder /src/build/release/Sage.jar /opt/sagetv/server/Sage.jar

WORKDIR /opt/sagetv/server

# FFmpeg setup — prefer SageTV's patched binary (supports -stdinctrl,
# -activefile, -dumpmetadata, -brokendts for full transcoder functionality).
# If the patched build failed, fall back to stock FFmpeg with a compatibility
# wrapper that strips the unsupported flags (basic transcoding still works,
# but dynamic rate adaptation and active-file playback won't be available).
COPY docker/ffmpeg-wrapper.sh /usr/local/bin/ffmpeg-wrapper
RUN chmod +x /usr/local/bin/ffmpeg-wrapper \
    && if [ -x /opt/sagetv/server/ffmpeg ]; then \
         echo "Using SageTV patched FFmpeg (full feature support)"; \
       else \
         echo "Patched FFmpeg not built — falling back to stock + wrapper"; \
         mv /usr/bin/ffmpeg /usr/local/bin/ffmpeg.real; \
         cp /usr/local/bin/ffmpeg-wrapper /usr/local/bin/ffmpeg; \
         ln -sf /usr/local/bin/ffmpeg /opt/sagetv/server/ffmpeg; \
       fi \
    && mkdir -p /var/media/videos /var/media/pictures /var/media/music \
    /opt/sagetv/server/logs \
    && chmod -R 755 /opt/sagetv \
    && chown -R sagetv:sagetv /opt/sagetv /var/media

# Expose SageTV ports:
#   8080  - Web UI (Jetty)
#   7818  - SageTV client connections
#   31099 - Placeshifter
EXPOSE 8080 7818 31099

# Volumes for persistent data (config and media only, NOT the server dir)
VOLUME ["/opt/sagetv/config", "/var/media"]

ENV LD_LIBRARY_PATH=/opt/sagetv/server:/opt/sagetv/server/lib:/opt/sagetv/server/irtunerplugins

# Run as sagetv user
USER sagetv

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
     "-cp", "Sage.jar:JARs/*", \
     "sage.Sage", "0", "0", "x", "sagetv Sage.properties"]
