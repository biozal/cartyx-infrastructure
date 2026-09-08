#!/usr/bin/env bash
set -euo pipefail
case "$(uname -s)/$(uname -m)" in
  Linux/x86_64) archive=Linux-64bit; digest=2ae6fe3ee734b7fdf11335663e18c75ea12dccc76062f09f164a3b0f8be4371a ;;
  Linux/aarch64) archive=Linux-ARM64; digest=b94ce1976bbf3c15b514b605ee88be7c6d94a29be2302847ff01cb794d47aad5 ;;
  *) echo 'This installer supports the two Linux CI architectures' >&2; exit 1 ;;
esac
mkdir -p .local/tools
curl --fail --silent --show-error --location --retry 3 \
  "https://github.com/aquasecurity/trivy/releases/download/v0.74.0/trivy_0.74.0_${archive}.tar.gz" \
  -o .local/tools/trivy.tar.gz
printf '%s  %s\n' "$digest" .local/tools/trivy.tar.gz | sha256sum --check
tar -xzf .local/tools/trivy.tar.gz -C .local/tools trivy
