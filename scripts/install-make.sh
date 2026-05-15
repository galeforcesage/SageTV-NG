#!/bin/bash
# install-make.sh - run on docker host. Installs build tools as root in container.
set -euo pipefail
docker exec --user root sagetv-mine bash -c '
set -e
apt-get update 2>&1 | tail -3
DEBIAN_FRONTEND=noninteractive apt-get install -y make autoconf automake libtool pkg-config m4 2>&1 | tail -5
echo --- which ---
which make autoconf automake libtool pkg-config m4
echo --- versions ---
make --version | head -1
autoconf --version | head -1
'
