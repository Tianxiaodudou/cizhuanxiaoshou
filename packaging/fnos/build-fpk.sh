#!/usr/bin/env bash
# 构建 fnOS FPK 安装包（服务端 S13 网关 + 内置 JRE）。
#
# 用法: build-fpk.sh <version> <server-install-dist-dir> <jre-tar.gz> <out.fpk>
#
# FPK 结构（与飞牛现网包一致）：
#   manifest            纯文本 key = value，checksum = md5(app.tgz)
#   app.tgz             应用载荷（bin/ lib/ jre/）
#   cmd/                main + install/upgrade/config/uninstall 生命周期脚本
#   config/privilege   运行身份
#   config/resource    宿主能力声明
#   ICON*.PNG           图标
set -euo pipefail

VERSION="${1:?usage: build-fpk.sh <version> <server-dist> <jre-tgz> <out.fpk>}"
SERVER_DIST="${2:?server install dist dir}"
JRE_TGZ="${3:?jre tar.gz}"
OUT_FPK="${4:?output fpk path}"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

APP="$WORK/app"
mkdir -p "$APP"

echo "[fpk] 复制服务端分发目录: $SERVER_DIST"
cp -R "$SERVER_DIST"/. "$APP"/

echo "[fpk] 解压内置 JRE"
mkdir -p "$APP/jre"
tar -xzf "$JRE_TGZ" -C "$APP/jre" --strip-components=1
"$APP/jre/bin/java" -version 2>&1 | head -2

echo "[fpk] 复制生命周期脚本与图标"
cp -R "$HERE/cmd" "$WORK/cmd"
cp -R "$HERE/config" "$WORK/config"
chmod +x "$WORK"/cmd/*
cp "$HERE/ICON.PNG" "$WORK/ICON.PNG"
cp "$HERE/ICON_256.PNG" "$WORK/ICON_256.PNG"

echo "[fpk] 打包 app.tgz"
tar -czf "$WORK/app.tgz" -C "$APP" .
CHECKSUM="$(md5sum "$WORK/app.tgz" | cut -d' ' -f1)"
SIZE="$(du -h "$WORK/app.tgz" | cut -f1)"

echo "[fpk] 生成 manifest (version=$VERSION, app.tgz=$SIZE, md5=$CHECKSUM)"
cat > "$WORK/manifest" <<EOF
appname               = jiancai
version               = $VERSION
display_name          = 建材进销存
desc                  = <p><b>建材门店进销存服务端</b>（S13 网关 + SQLite + 审计）。提供下单、改单、退单校验、差异备注、结算审核、80mm 打印渲染与审计留痕；手机端通过 HTTP 接入，金额 / 单号 / 状态全部由服务端产出。</p><p><b>默认端口 8080</b>，数据保存在应用 var 目录（<code>jiancai.db</code>），首次启动自动建库并迁移到最新版本；首次可通过环境变量或管理端创建管理员。</p><p><b>内置 JRE 17</b>，不依赖宿主 Java，也不修改系统环境。</p>
platform              = x86
source                = thirdparty
maintainer            = Tianxiaodudou
maintainer_url        = https://github.com/Tianxiaodudou
distributor           = A鱼儿
os_min_version        = 1.2.0401
ctl_stop              = true
changelog             = $VERSION: 建材进销存服务端首包（S09-S14 + 客户端同版本配套）
checksum              = $CHECKSUM
EOF

echo "[fpk] 组装 $OUT_FPK"
mkdir -p "$(dirname "$OUT_FPK")"
tar -cf "$OUT_FPK" -C "$WORK" manifest app.tgz cmd config ICON.PNG ICON_256.PNG

echo "[fpk] 校验产物"
tar -tf "$OUT_FPK" | sed 's/^/  /'
echo "[fpk] 完成: $OUT_FPK ($(du -h "$OUT_FPK" | cut -f1))"
