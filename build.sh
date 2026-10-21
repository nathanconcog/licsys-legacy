#!/usr/bin/env bash
# Compile LICSYS batch programs with GnuCOBOL 3.x
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p bin
for prog in DMRPTS LICUPG REGFEE; do
  cobc -x -std=default -I copybooks -o "bin/$prog" "src/$prog.cbl"
done
echo "build ok"
