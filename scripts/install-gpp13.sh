#!/bin/bash
set -e
docker exec --user root sagetv-mine bash -c '
DEBIAN_FRONTEND=noninteractive apt-get install -y g++-13 2>&1 | tail -10
echo --- result ---
ls /usr/lib/gcc/x86_64-linux-gnu/13/cc1plus 2>&1
'
