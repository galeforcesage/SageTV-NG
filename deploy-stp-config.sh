#!/bin/bash
# Deploy Samsung TV Plus plugin config to Sage.properties
set -e

PROPS=/opt/sagetv/server/Sage.properties

# Backup
cp "$PROPS" "${PROPS}.bak.20260407"
echo "Backup created"

# Add Samsung TV Plus settings (only if not already present)
if ! grep -q "samsungtvplus/enabled" "$PROPS"; then
  printf '\n# Samsung TV Plus IPTV Plugin\n' >> "$PROPS"
  echo 'samsungtvplus/enabled=true' >> "$PROPS"
  echo 'samsungtvplus/region=us' >> "$PROPS"
  echo 'samsungtvplus/num_tuners=2' >> "$PROPS"
  echo "Added samsungtvplus settings"
else
  echo "samsungtvplus settings already exist"
fi

# Set EPG import plugin
if grep -q '^epg/epg_import_plugin=' "$PROPS"; then
  sed -i 's|^epg/epg_import_plugin=.*|epg/epg_import_plugin=sage.samsungtvplus.SamsungTVPlusEPGPlugin|' "$PROPS"
  echo "Updated epg_import_plugin"
else
  echo 'epg/epg_import_plugin=sage.samsungtvplus.SamsungTVPlusEPGPlugin' >> "$PROPS"
  echo "Added epg_import_plugin"
fi

echo "--- Verification ---"
grep -E 'samsungtvplus|epg_import_plugin' "$PROPS"
