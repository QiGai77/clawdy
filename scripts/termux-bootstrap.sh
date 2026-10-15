#!/data/data/com.termux/files/usr/bin/bash
# Clawdy — one-time Termux setup. Run inside Termux:
#   bash termux-bootstrap.sh
set -e
mkdir -p ~/.termux
grep -q '^allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings || true
pkg update -y
pkg install -y git python nodejs-lts pandoc imagemagick poppler qpdf libxml2 libxslt
pkg install -y x11-repo tur-repo || true
pkg install -y chromium || echo "chromium not available on this device/repo"
pip install --upgrade python-docx python-pptx openpyxl markdown pypdf
mkdir -p ~/clawdy/default
cd ~/clawdy && npm init -y >/dev/null 2>&1 || true
npm install playwright-core >/dev/null 2>&1 || echo "playwright-core install failed"
termux-setup-storage || true
mkdir -p ~/storage/shared/Clawdy 2>/dev/null || true
cat <<'MSG'
Done. In Playwright use:
  const { chromium } = require('playwright-core');
  const b = await chromium.launch({ executablePath: '/data/data/com.termux/files/usr/bin/chromium-browser', args: ['--no-sandbox','--headless=new'] });
Now grant Clawdy: Settings → Apps → Clawdy → Permissions → "Run commands in Termux environment".
MSG
