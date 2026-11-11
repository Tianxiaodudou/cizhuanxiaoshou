# czxs FPK 打包

使用飞牛官方 `fnpack` 工具（https://developer.fnnas.com/docs/cli/fnpack/）：

1. 下载 fnpack 1.2.3 Windows/Linux 二进制；
2. 把构建产物（`fpk-server/czxs/build/install/czxs/lib` 与内置 JRE）放到本目录 `app/lib`、`app/jre`；
3. 在打包目录执行 `fnpack build`（或 `fnpack build --directory packaging/fnos-czxs`）。

目录结构遵循官方规范：`app/`（含 `ui/config` 网关入口）、`cmd/`、`config/privilege`、`config/resource`、`wizard/`、`manifest`、`ICON*.PNG`。不要手写 tar：该格式由 fnpack 生成 `app.tgz` 和 manifest 校验信息。
