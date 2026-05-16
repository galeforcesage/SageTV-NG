#!/bin/bash
set -e
FILE=/opt/sagetv/server/Sage.properties
cp "$FILE" "$FILE.bak.$(date +%s)"
cat >> "$FILE" <<'PROPS'

# === ATSC1 OTA PSIP scanner ===
epg/ota_scan_enabled=true
epg/ota_scan_allow_dual_tuner=true
epg/ota_scan_device_id=104D0AA7
epg/ota_scan_device_ip=192.168.0.92
PROPS
echo "--- verify ---"
tail -8 "$FILE"
