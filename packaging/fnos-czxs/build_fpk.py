"""把 czxs 打成飞牛 FPK（tar），带 ui/config 网关入口与可执行 cmd 脚本。

用法：python packaging/fnos-czxs/build_fpk.py <version> <installDist目录> <jre.tar.gz> <out.fpk>
"""
import hashlib
import io
import os
import shutil
import sys
import tarfile
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
CLEAN = []


def add_dir(tar, src, arc, mode_default=0o644, exec_names=()):
    for base, dirs, files in os.walk(src):
        dirs.sort()
        for f in sorted(files):
            full = os.path.join(base, f)
            rel = os.path.relpath(full, src)
            arcname = os.path.join(arc, rel) if rel != "." else arc
            info = tar.gettarinfo(full, arcname)
            info.mode = 0o755 if os.path.basename(full) in exec_names else mode_default
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            with open(full, "rb") as fh:
                tar.addfile(info, fh)


def build(version, dist_dir, jre_tgz, out_fpk):
    work = os.path.join(ROOT, ".work")
    shutil.rmtree(work, ignore_errors=True)
    payload = os.path.join(work, "payload")
    os.makedirs(payload, exist_ok=True)

    # 1) 载荷：lib/（fat runtime）+ ui/ + jre/
    shutil.copytree(os.path.join(dist_dir, "lib"), os.path.join(payload, "lib"))
    shutil.copytree(os.path.join(ROOT, "ui"), os.path.join(payload, "ui"))
    with tarfile.open(jre_tgz, "r:gz") as jt:
        jt.extractall(os.path.join(payload, "jre"), filter="data") if hasattr(tarfile, "data_filter") else jt.extractall(os.path.join(payload, "jre"))

    # jre 顶层目录可能带一层 jdk-17.x，压平
    jre_dir = os.path.join(payload, "jre")
    entries = [e for e in os.listdir(jre_dir) if e != "bin"]
    if "bin" not in os.listdir(jre_dir) and entries:
        inner = os.path.join(jre_dir, entries[0])
        if os.path.isdir(inner):
            for e in os.listdir(inner):
                shutil.move(os.path.join(inner, e), os.path.join(jre_dir, e))
            shutil.rmtree(inner, ignore_errors=True)

    app_tgz = os.path.join(work, "app.tgz")
    with tarfile.open(app_tgz, "w:gz") as t:
        add_dir(t, payload, ".")
    checksum = hashlib.md5(open(app_tgz, "rb").read()).hexdigest()
    size = os.path.getsize(app_tgz)
    print(f"[fpk] app.tgz {size/1048576:.1f} MB md5={checksum}")

    manifest = f"""appname               = czxs
version               = {version}
display_name          = 瓷砖店销售系统
desc                  = <p><b>瓷砖店销售系统</b>（czxs）：四类单据 + 备注单 + 型号库 + 客户价格 + 打印队列 + 结算。</p><p>管理端经飞牛统一网关访问 /app/czxs/admin；客户端 API 走独立 TCP 端口（默认 8080，仅 JSON）。</p>
platform              = x86
source                = thirdparty
maintainer            = Tianxiaodudou
maintainer_url        = https://github.com/Tianxiaodudou
distributor           = A鱼儿
os_min_version        = 1.2.0401
service_port          = 8080
checkport             = true
ctl_stop              = true
desktop_uidir         = ui
desktop_applaunchname = czxs.main
disable_authorization_path = true
changelog             = {version}: czxs v1.1 首包（管理端 + 客户端 API + 打印队列）
checksum              = {checksum}
"""
    manifest_path = os.path.join(work, "manifest")
    open(manifest_path, "w", encoding="utf-8", newline="\n").write(manifest)

    # 2) 外壳：manifest + app.tgz + cmd/ + config/ + 图标
    os.makedirs(os.path.dirname(out_fpk) or ".", exist_ok=True)
    with tarfile.open(out_fpk, "w") as t:
        t.add(manifest_path, "manifest")
        t.add(app_tgz, "app.tgz")
        for f in sorted(os.listdir(os.path.join(ROOT, "cmd"))):
            p = os.path.join(ROOT, "cmd", f)
            info = t.gettarinfo(p, "cmd/" + f)
            info.mode = 0o755
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            with open(p, "rb") as fh:
                t.addfile(info, fh)
        add_dir(t, os.path.join(ROOT, "config"), "config")
        for icon in ("ui/images/icon_64.png", "ui/images/icon_256.png"):
            p = os.path.join(ROOT, icon)
            if os.path.exists(p):
                t.add(p, os.path.basename(icon).replace("icon_64", "ICON").replace("icon_256", "ICON_256"))
    print(f"[fpk] 写入 {out_fpk} ({os.path.getsize(out_fpk)/1048576:.1f} MB)")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        print(__doc__)
        sys.exit(2)
    build(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
