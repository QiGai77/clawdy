#!/data/data/com.termux/files/usr/bin/bash
# Superseded by clawdy-setup.sh (Clawdy 0.3.0). Kept so old links keep working.
exec bash "$(dirname "$0")/clawdy-setup.sh" "$@" 2>/dev/null || curl -fsSL https://raw.githubusercontent.com/QiGai77/clawdy/main/scripts/clawdy-setup.sh | bash -s -- "$@"
