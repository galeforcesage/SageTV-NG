#!/bin/bash
###############################################################################
# SageTV Java 21 Docker Build Script
#
# Clones from galeforcesage/SageTV-mine (all patches pre-applied)
# and builds + runs a Docker container.
#
# Prerequisites: git, docker
#
# Usage:
#   chmod +x setup-sagetv-docker.sh
#   ./setup-sagetv-docker.sh
###############################################################################
set -e

REPO_URL="https://github.com/galeforcesage/SageTV-mine.git"
BRANCH="appmod/java-upgrade-20260328165139"

echo "================================================"
echo " SageTV Java 21 + Weather Meteo Docker Setup"
echo "================================================"

# ------------------------------------------------------------------
# Step 0: Check prerequisites
# ------------------------------------------------------------------
for cmd in git docker; do
  if ! command -v $cmd &> /dev/null; then
    echo "ERROR: $cmd is required but not installed."
    echo "  Install with: sudo apt-get install -y $cmd"
    exit 1
  fi
done

# ------------------------------------------------------------------
# Step 1: Clone the repo (all patches already applied)
# ------------------------------------------------------------------
echo ""
echo "[1/3] Cloning SageTV repository..."
mkdir -p ~/sourcecode
cd ~/sourcecode

if [ -d "sagetv" ]; then
  echo "  -> 'sagetv' directory already exists, pulling latest..."
  cd sagetv
  git fetch origin
  git checkout "$BRANCH" 2>/dev/null || git checkout -b "$BRANCH" "origin/$BRANCH"
  git pull origin "$BRANCH" || true
else
  git clone -b "$BRANCH" "$REPO_URL" sagetv
  cd sagetv
fi

SAGETV_DIR="$(pwd)"
echo "  -> Working directory: $SAGETV_DIR"
echo "  -> Branch: $BRANCH"
echo "  -> All Java 21 patches + Weather Meteo already included"

# ------------------------------------------------------------------
# Step 2: Build Docker image
# ------------------------------------------------------------------
echo ""
echo "[2/3] Building Docker image (this will take several minutes)..."

# Stop and remove existing container if running
docker stop sagetv-server 2>/dev/null || true
docker rm sagetv-server 2>/dev/null || true

docker build -t sagetv:java21 . 2>&1 | tee docker-build.log

# ------------------------------------------------------------------
# Step 3: Run the container
# ------------------------------------------------------------------
echo ""
echo "[3/3] Starting SageTV container..."
docker run -d \
  --name sagetv-server \
  --restart unless-stopped \
  --network host \
  -v sagetv-videos:/var/media/videos \
  -v sagetv-pictures:/var/media/pictures \
  -v sagetv-music:/var/media/music \
  sagetv:java21

echo ""
echo "================================================"
echo " SageTV is starting!"
echo "================================================"
echo ""
echo " View logs:     docker logs -f sagetv-server"
echo " Stop:          docker stop sagetv-server"
echo " Start:         docker start sagetv-server"
echo " Remove:        docker rm -f sagetv-server"
echo " Rebuild:       docker build -t sagetv:java21 ."
echo ""
echo " Ports:"
echo "   8080  - Web UI"
echo "   7818  - SageTV client connections"
echo "   31099 - Placeshifter"
echo ""
echo " Build log saved to: $(pwd)/docker-build.log"
echo "================================================"
