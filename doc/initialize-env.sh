set -e
export SDKMAN_DIR="$HOME/.sdkman"

if [[ ! -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
  curl -s "https://get.sdkman.io?ci=true" | bash
fi

set +u
source "$SDKMAN_DIR/bin/sdkman-init.sh"

sdk install java 25.0.2-graalce
sdk install kotlin 2.4.10
sdk install gradle 9.6.1
hash -r
set -u