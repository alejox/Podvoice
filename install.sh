#!/usr/bin/env bash
# Instala Podvoice Enhance en la carpeta de scripts de DaVinci Resolve (macOS / Linux).
set -e
case "$(uname)" in
  Darwin) DIR="$HOME/Library/Application Support/Blackmagic Design/DaVinci Resolve/Fusion/Scripts/Utility" ;;
  *)      DIR="$HOME/.local/share/DaVinciResolve/Fusion/Scripts/Utility" ;;
esac
mkdir -p "$DIR"
cp "$(dirname "$0")/Podvoice Enhance.py" "$DIR/"
echo "Instalado en: $DIR"
echo "En Resolve: Workspace > Scripts > Podvoice Enhance"
