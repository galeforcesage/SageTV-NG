#!/bin/bash
set -e
FILE=/opt/sagetv/server/Sage.properties
cp "$FILE" "$FILE.bak.$(date +%s)"
cat >> "$FILE" <<'PROPS'

# === ATSC1 OTA PSIP scanner ===
epg/ota_scan_enabled=true
epg/ota_scan_allow_dual_tuner=true
# Set these via environment variables before running this script.
epg/ota_scan_device_id=${OTA_SCAN_DEVICE_ID:-REPLACE_ME}
epg/ota_scan_device_ip=${OTA_SCAN_DEVICE_IP:-REPLACE_ME}
PROPS
echo "--- verify ---"
tail -8 "$FILE"
