#!/usr/bin/env bash
# Replace local page tunnels and serve all five webpages from the running lab.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: webpages.sh' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/lib/lifecycle.sh"
context=kind-shardshop
require_tools kubectl lsof
page_access_clear_ports
page_access
