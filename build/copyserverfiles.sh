#!/bin/sh
#
# Copyright 2015 The SageTV Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Copy all the files needed for the server into the
# serverrelease folder
rm -rf serverrelease
mkdir serverrelease
cp release/Sage.jar ./serverrelease/
cp -R serverfiles/* ./serverrelease/
cp so/*.so ./serverrelease/
rm serverrelease/libSageX11.so
cp so/libav* ./serverrelease/
mkdir ./serverrelease/irtunerplugins
cp so/irtunerplugins/*.so ./serverrelease/irtunerplugins/

cp elf/ffmpeg ./serverrelease/
cp elf/jpegtran ./serverrelease/
cp elf/comskip ./serverrelease/ 2>/dev/null || true
mkdir -p ./serverrelease/comskip_profiles
cp ../comskip/*.ini ./serverrelease/comskip_profiles/ 2>/dev/null || true
cp ../install/config/RemoteClients.properties.defaults ./serverrelease
cp ../install/config/Sage.properties.defaults ./serverrelease
mkdir ./serverrelease/STVs
# SageTV3 is now a plugin
#cp -R ../stvs/SageTV3 ./serverrelease/STVs/
cp -R ../stvs/SageTV7 ./serverrelease/STVs/
rm ./serverrelease/STVs/SageTV7/*.stv
mkdir ./serverrelease/fonts
cp -R ../third_party/DejaVuFonts/*.ttf ./serverrelease/fonts/
cp ../install/config/*.frq ./serverrelease/
mkdir ./serverrelease/JARs
cp ../third_party/Oracle/*.jar ./serverrelease/JARs/
cp ../third_party/Apache/*.jar ./serverrelease/JARs/
cp ../third_party/Lucene/*.jar ./serverrelease/JARs/
# Maven-resolved runtime classpath (org.jupnp, slf4j-api, logback-*, etc.)
# staged here by the Gradle copyRuntimeJars task. Note: Gradle's buildDir
# is overridden in build.gradle to 'buildoutput' (so `gradle clean` won't
# nuke the legacy build/ tree), hence ../buildoutput/runtime-jars/. The
# wildcard cp may overwrite vendored jars of the same name (e.g. lucene/
# jcifs/vecmath pulled in via implementation files()) which is harmless:
# identical bits.
cp ../buildoutput/runtime-jars/*.jar ./serverrelease/JARs/ 2>/dev/null || true
