#!/bin/bash
# Run on the Docker host after stopping the container
# Patches Sage.properties and restarts
set -e

CID="${SAGETV_CONTAINER_ID:?Set SAGETV_CONTAINER_ID to your Docker container ID}"
PROPS_PATH=/opt/sagetv/server/Sage.properties

echo "=== Stopping container ==="
docker stop $CID

echo "=== Copying Sage.properties from stopped container ==="
docker cp $CID:$PROPS_PATH /tmp/Sage.properties

echo "=== Patching Sage.properties ==="
# Fix samsungtvplus/enabled
sed -i 's|^samsungtvplus/enabled=.*|samsungtvplus/enabled=true|' /tmp/Sage.properties
# If not present, add it
grep -q '^samsungtvplus/enabled=' /tmp/Sage.properties || echo 'samsungtvplus/enabled=true' >> /tmp/Sage.properties

# Fix region
grep -q '^samsungtvplus/region=' /tmp/Sage.properties || echo 'samsungtvplus/region=us' >> /tmp/Sage.properties

# Fix num_tuners
grep -q '^samsungtvplus/num_tuners=' /tmp/Sage.properties || echo 'samsungtvplus/num_tuners=2' >> /tmp/Sage.properties

# Fix EPG import plugin
sed -i 's|^epg/epg_import_plugin=.*|epg/epg_import_plugin=sage.samsungtvplus.SamsungTVPlusEPGPlugin|' /tmp/Sage.properties

echo "=== Verification ==="
grep -E 'samsungtvplus|epg_import_plugin' /tmp/Sage.properties

echo "=== Copying patched Sage.properties back ==="
docker cp /tmp/Sage.properties $CID:$PROPS_PATH

echo "=== Starting container ==="
docker start $CID

echo "=== Done ==="
