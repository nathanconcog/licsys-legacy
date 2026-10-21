#!/usr/bin/env bash
# Local equivalent of jcl/LICNIGHT.jcl
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
bin/DMRPTS
bin/LICUPG
bin/REGFEE
