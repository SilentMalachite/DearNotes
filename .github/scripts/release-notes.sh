#!/usr/bin/env bash
# GitHub Release の本文を作る。CHANGELOG.md から指定した版の節を抜き出し、ダウンロードの案内を足す。
# 使い方: release-notes.sh 1.0.1 [CHANGELOG.md]
set -euo pipefail

version="$1"
changelog="${2:-CHANGELOG.md}"

# 「## [1.0.1] - 日付」の次の行から、次の「## [」の手前まで
section="$(awk -v v="$version" '
    index($0, "## [" v "]") == 1 { found = 1; next }
    found && /^## \[/ { exit }
    found && /^\[[^]]+\]: / { exit }
    found { print }
' "$changelog")"

if [ -z "$(printf '%s' "$section" | tr -d '[:space:]')" ]; then
    echo "CHANGELOG.md に [$version] の節がありません" >&2
    exit 1
fi

# 前後の空行を落とす
section="$(printf '%s\n' "$section" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')"

cat <<EOF
## ダウンロード

- macOS（Apple Silicon）: \`DearNotes-$version.dmg\`
- Windows: \`DearNotes-$version.msi\`

Intel Mac はサポート外です。Java は同梱しているので、別に入れる必要はありません。

インストーラーには署名をしていないので、初めて開くときに OS の警告が出ます。許可の手順は README の「署名していないアプリを開く」を見てください。

$section
EOF
