import { execFile } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, resolve, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WORKSPACE = resolve(HERE, '..'); // D:\建材系统
const STATE_DIR = join(WORKSPACE, '.workbench');
const STATE_FILE = join(STATE_DIR, 'state.json');

// ────────────────────────────────────────────────────────────────
// 开发计划数据源（模块化拆分方案）：28 个代码模块 + /shared + /docs
// ────────────────────────────────────────────────────────────────
const GLOBAL_RULES = [
  { id: 'R1', text: '目录即模块，模块只通过 Port 接口通信，接口只增不改。' },
  { id: 'R2', text: '返回 DTO，不返回 Entity。' },
  { id: 'R3', text: '禁止跨模块访问 repository / 表；需要别人的数据就调对方的 Port。' },
  { id: 'R4', text: '跨模块事务由 db-core 的 TxRunner 统一包裹，Port 方法接收事务上下文。' },
  { id: 'R5', text: '金额、单号、批号类型一律服务端产出，客户端只展示、零计算。' },
  { id: 'R6', text: '打印只在服务端（S12），客户端全局 deny 打印相关关键词。' },
  { id: 'R7', text: '权限统一在 api-gateway（S13）拦截，业务模块不各自判断权限。' },
  { id: 'R8', text: '单号仅 S08 通过 SerialPort 获取，其它模块不得自行生成单号。' },
  { id: 'R9', text: '共享定义只放 /shared（两端唯一真源），禁止两端各写一套字符串。' },
  { id: 'R10', text: 'SQL 只能出现在各模块自己的 repository 内，表归属各模块。' },
];

// layer: server | client | shared | docs
// calls: 依赖的模块 id（用于依赖图、任务卡）
const MODULES = [
  // —— 契约层 ——
  { id: 'SHARED', name: 'shared 契约层', layer: 'shared', phase: 1, dir: '/shared',
    duty: '两端唯一真源：DTO、枚举、错误码、单号前缀、单位与装箱规格，带 contractVersion。',
    ports: ['enums/', 'serial/', 'errors/', 'units/', 'dto/'],
    calls: [], write: ['shared/**'],
    forbidden: ['禁止实现业务逻辑', '禁止两端各写一套字符串', '禁止删除或改动已有字段签名'],
    accept: ['双端引用同一份契约，无魔法字符串', 'contractVersion 随变更递增'] },

  // —— 服务端 ——
  { id: 'S00', name: 'db-core', layer: 'server', phase: 1, dir: 'fpk-server/db-core',
    duty: '唯一 SQLite 连接、事务、迁移、:memory: 测试库。',
    ports: ['TxRunner.run {}', 'Migrator'],
    calls: [], write: ['fpk-server/db-core/**'],
    forbidden: ['禁止业务逻辑', '禁止其它模块自行开连接', 'SQL 只出现在 repository 内'],
    accept: ['跨模块写操作可被同一事务包裹', '迁移幂等', ':memory: 测试库可用'] },
  { id: 'S07', name: 'serial', layer: 'server', phase: 1, dir: 'fpk-server/serial',
    duty: '单号生成：前缀+年+月日+全局序号，事务取号+唯一约束兜底。',
    ports: ['SerialPort.next(type)'],
    calls: ['S00'], write: ['fpk-server/serial/**'],
    forbidden: ['禁止业务模块自行拼单号', '禁止非事务取号', '禁止客户端生成单号'],
    accept: ['并发取号不重号', '前缀与年月日正确'] },
  { id: 'S01', name: 'config', layer: 'server', phase: 1, dir: 'fpk-server/config',
    duty: '部署级商家名称、部署配置（客户端不可传）。',
    ports: ['ConfigPort.getMerchantName()'],
    calls: ['S00'], write: ['fpk-server/config/**'],
    forbidden: ['禁止客户端传部署配置', '禁止写死商家名'],
    accept: ['商家名称由服务端配置生效'] },
  { id: 'S02', name: 'auth', layer: 'server', phase: 1, dir: 'fpk-server/auth',
    duty: '登录、token 签发与校验、账号密码。',
    ports: ['AuthPort.login / verify'],
    calls: ['S00'], write: ['fpk-server/auth/**'],
    forbidden: ['禁止明文存密码', '禁止鉴权下沉到客户端'],
    accept: ['登录/校验通过', '无效 token 被拒'] },
  { id: 'S03', name: 'account', layer: 'server', phase: 1, dir: 'fpk-server/account',
    duty: '账号 CRUD（仅管理员）。',
    ports: ['AccountAdminPort'],
    calls: ['S00', 'S02'], write: ['fpk-server/account/**'],
    forbidden: ['禁止普通用户调用', '禁止模块内自判权限'],
    accept: ['非管理员被 api-gateway 拒绝'] },

  { id: 'S04', name: 'customer', layer: 'server', phase: 2, dir: 'fpk-server/customer',
    duty: '客户建档、按电话查询、地址快照。',
    ports: ['CustomerPort.findByPhone / create / get'],
    calls: ['S00'], write: ['fpk-server/customer/**'],
    forbidden: ['禁止访问其它模块的表', '返回 DTO 而非 Entity'],
    accept: ['contract test 通过', '按电话查询正确'] },
  { id: 'S05', name: 'category', layer: 'server', phase: 2, dir: 'fpk-server/category',
    duty: '品类/型号库 CRUD（仅管理员）、批号类型。',
    ports: ['CategoryPort.list / get / create / update / delete'],
    calls: ['S00'], write: ['fpk-server/category/**'],
    forbidden: ['禁止普通用户改型号库', '禁止模块内自判权限'],
    accept: ['管理员 CRUD 通过', '普通用户被拒'] },
  { id: 'S06', name: 'pricing', layer: 'server', phase: 2, dir: 'fpk-server/pricing',
    duty: '恒定值（客户+型号+批号）、当日可改、跨日锁、管理员改价留痕。',
    ports: ['PricePort.resolve / changePrice'],
    calls: ['S00', 'S04', 'S05'], write: ['fpk-server/pricing/**'],
    forbidden: ['禁止订单模块自行算价', '禁止删除改价留痕'],
    accept: ['跨日锁生效', '改价有留痕', 'PriceChanged 触发重算'] },
  { id: 'S08', name: 'order', layer: 'server', phase: 2, dir: 'fpk-server/order',
    duty: '订单抬头/明细、幂等、乐观锁、改当日单、应收派生。',
    ports: ['OrderPort.create / update / today / getRecent / getReceivable'],
    calls: ['S07', 'S06', 'S04', 'S05'], write: ['fpk-server/order/**'],
    forbidden: ['禁止自行拼单号', '禁止自行算价', '禁止直连其它模块表', '禁止客户端算金额'],
    accept: ['幂等（client_request_id）通过', '乐观锁通过', '改当日单/应收派生正确'] },

  { id: 'S09', name: 'return-check', layer: 'server', phase: 3, dir: 'fpk-server/return-check',
    duty: '可退数量计算、批次校验（规则独立）。',
    ports: ['ReturnCheckPort.availableBatches / validate'],
    calls: ['S08'], write: ['fpk-server/return-check/**'],
    forbidden: ['禁止直接访问 order 表', '禁止改订单数据'],
    accept: ['只通过 OrderPort 读', '规则可独立升级'] },
  { id: 'S10', name: 'note', layer: 'server', phase: 3, dir: 'fpk-server/note',
    duty: '差异备注单（关联原单、不改原单、作废）。',
    ports: ['NotePort.create / void'],
    calls: ['S08'], write: ['fpk-server/note/**'],
    forbidden: ['禁止修改原单', '禁止直接访问 order 表'],
    accept: ['原单不被改动', '作废用例通过'] },
  { id: 'S11', name: 'settlement', layer: 'server', phase: 3, dir: 'fpk-server/settlement',
    duty: '结算记录、当场判定、审核、已收/未收读模型。',
    ports: ['SettlePort.submit / audit / getBalance'],
    calls: ['S08', 'S03'], write: ['fpk-server/settlement/**'],
    forbidden: ['禁止直接访问 order 表', '已收金额永远不动', '应收从 S08 重新读取'],
    accept: ['审核流通过', '已收金额不被改动'] },
  { id: 'S12', name: 'print', layer: 'server', phase: 3, dir: 'fpk-server/print',
    duty: '打印渲染（80mm、五列/双行、换算、打印次数）。',
    ports: ['PrintPort.render(orderNo / noteNo)'],
    calls: ['S08', 'S01'], write: ['fpk-server/print/**'],
    forbidden: ['禁止客户端打印', '禁止直接访问 order 表'],
    accept: ['80mm 渲染正确', '打印次数记录', '客户端 deny 生效'] },
  { id: 'S13', name: 'api-gateway', layer: 'server', phase: 3, dir: 'fpk-server/api-gateway',
    duty: '路由、权限拦截、错误码映射、幂等键校验。',
    ports: ['HTTP 路由'],
    calls: ['S02', 'S03', 'S04', 'S05', 'S06', 'S07', 'S08', 'S09', 'S10', 'S11', 'S12'], write: ['fpk-server/api-gateway/**'],
    forbidden: ['禁止实现业务逻辑（只转发/拦截）'],
    accept: ['权限在 API 层拦截', '错误码统一', '幂等键校验'] },
  { id: 'S14', name: 'observability', layer: 'server', phase: 3, dir: 'fpk-server/observability',
    duty: '日志、审计留痕（改价、审核）。',
    ports: ['AuditPort'],
    calls: ['S00'], write: ['fpk-server/observability/**'],
    forbidden: ['禁止参与业务决策'],
    accept: ['改价/审核有留痕'] },

  // —— 客户端 ——
  { id: 'C00', name: 'core-network', layer: 'client', phase: 4, dir: 'android-app/core-network',
    duty: 'HTTP 客户端、token 注入、错误码统一解析。',
    ports: ['ApiClient'],
    calls: [], write: ['android-app/core-network/**'],
    forbidden: ['禁止业务逻辑', '金额/单号只展示不计算'],
    accept: ['错误码统一解析', 'token 自动注入'] },
  { id: 'C01', name: 'core-session', layer: 'client', phase: 4, dir: 'android-app/core-session',
    duty: '登录态、导航守卫（唯一 token 判断点）。',
    ports: ['SessionGate'],
    calls: ['C00'], write: ['android-app/core-session/**'],
    forbidden: ['禁止其它地方判断 token'],
    accept: ['token 判断仅此一处'] },
  { id: 'C02', name: 'core-ui', layer: 'client', phase: 4, dir: 'android-app/core-ui',
    duty: '通用组件、主题、按钮 @ui-button 注解。',
    ports: ['组件库'],
    calls: [], write: ['android-app/core-ui/**'],
    forbidden: ['禁止耦合具体业务'],
    accept: ['组件可复用'] },
  { id: 'C03', name: 'app-shell', layer: 'client', phase: 4, dir: 'android-app/app-shell',
    duty: '底栏 3 tab、路由表、首页容器。',
    ports: ['NavGraph'],
    calls: ['C05', 'C06', 'C07', 'C08', 'C09', 'C10', 'C11', 'C12'], write: ['android-app/app-shell/**'],
    forbidden: ['禁止 feature 之间互相 import UI'],
    accept: ['路由跳转正常'] },
  { id: 'C04', name: 'feature-login', layer: 'client', phase: 4, dir: 'android-app/feature-login',
    duty: '登录页、服务端地址可改。',
    ports: ['LoginRoute'],
    calls: ['C01'], write: ['android-app/feature-login/**'],
    forbidden: ['禁止自行保存 token'],
    accept: ['登录可用', '服务端地址可改'] },
  { id: 'C05', name: 'feature-home', layer: 'client', phase: 4, dir: 'android-app/feature-home',
    duty: '5 按钮布局。',
    ports: ['HomeRoute'],
    calls: ['C02'], write: ['android-app/feature-home/**'],
    forbidden: ['禁止业务计算'],
    accept: ['按钮跳转正常'] },
  { id: 'C06', name: 'feature-order-entry', layer: 'client', phase: 4, dir: 'android-app/feature-order-entry',
    duty: '4 类录入页共用 OrderFormComponent，只传 orderType。',
    ports: ['OrderEntryRoute(orderType)'],
    calls: ['C00'], write: ['android-app/feature-order-entry/**'],
    forbidden: ['禁止客户端算金额/单号'],
    accept: ['4 类页共用组件', '提交走服务端'] },
  { id: 'C07', name: 'feature-query', layer: 'client', phase: 4, dir: 'android-app/feature-query',
    duty: '按电话查历史，只调聚合接口。',
    ports: ['QueryRoute'],
    calls: ['C00'], write: ['android-app/feature-query/**'],
    forbidden: ['禁止本地拼 SQL / 直连'],
    accept: ['只调聚合接口'] },
  { id: 'C08', name: 'feature-today', layer: 'client', phase: 4, dir: 'android-app/feature-today',
    duty: '改当日单列表 + 编辑（复用录入页壳）。',
    ports: ['TodayRoute'],
    calls: ['C06'], write: ['android-app/feature-today/**'],
    forbidden: ['禁止跨日改单（服务端锁）'],
    accept: ['复用录入壳', '跨日不可改'] },
  { id: 'C09', name: 'feature-note', layer: 'client', phase: 4, dir: 'android-app/feature-note',
    duty: '备注单 + 选原单（SelectOrderPage）。',
    ports: ['NoteRoute'],
    calls: ['C00'], write: ['android-app/feature-note/**'],
    forbidden: ['禁止修改原单'],
    accept: ['备注单正确关联原单'] },
  { id: 'C10', name: 'feature-settle', layer: 'client', phase: 4, dir: 'android-app/feature-settle',
    duty: '结算上传，成功/失败当场回显。',
    ports: ['SettleRoute'],
    calls: ['C00'], write: ['android-app/feature-settle/**'],
    forbidden: ['禁止客户端判定结算'],
    accept: ['上传与回显正确'] },
  { id: 'C11', name: 'feature-admin', layer: 'client', phase: 4, dir: 'android-app/feature-admin',
    duty: '型号库、价格、结算审核、账号四个子页。',
    ports: ['AdminRoute'],
    calls: ['C00'], write: ['android-app/feature-admin/**'],
    forbidden: ['禁止绕过服务端权限'],
    accept: ['4 子页可用', '权限由服务端把关'] },
  { id: 'C12', name: 'feature-settings', layer: 'client', phase: 4, dir: 'android-app/feature-settings',
    duty: '客户端配置 + 只读打印状态。',
    ports: ['SettingsRoute'],
    calls: ['C00'], write: ['android-app/feature-settings/**'],
    forbidden: ['禁止客户端打印', '打印关键词 deny'],
    accept: ['打印状态只读'] },

  // —— 收尾 ——
  { id: 'DOCS', name: '文档', layer: 'docs', phase: 5, dir: '/docs',
    duty: 'docs/routes.md、docs/features.md 与扫描产物。',
    ports: [], calls: [], write: ['docs/**'],
    forbidden: ['禁止与实现脱节'],
    accept: ['文档与代码实现一致'] },
  { id: 'SCAN', name: '按钮扫描脚本', layer: 'docs', phase: 5, dir: '/docs',
    duty: '按钮扫描脚本（对应打印关键词 deny hooks）。',
    ports: [], calls: [], write: ['docs/**'],
    forbidden: ['只读扫描，禁止改业务代码'],
    accept: ['能扫出违规关键词'] },
  { id: 'HOOKS', name: 'dsh hooks', layer: 'docs', phase: 5, dir: '/docs',
    duty: 'dsh hooks 配置。',
    ports: [], calls: [], write: ['docs/**'],
    forbidden: ['不改变模块边界'],
    accept: ['hooks 生效'] },
];

const PHASES = [
  { id: 1, name: '打地基', desc: '先把两边共用的约定、数据库内核、单号规则、部署信息和登录鉴权做好。这一步以挨个做为主。',
    modules: ['SHARED', 'S00', 'S07', 'S01', 'S02', 'S03'] },
  { id: 2, name: '主流程', desc: '按顺序做客户档案、型号库、价格、订单，这是整个系统的主干。',
    modules: ['S04', 'S05', 'S06', 'S08'] },
  { id: 3, name: '周边功能', desc: '退单校验、差异备注、结算、打印可以同时做，最后加服务网关和日志审计。',
    modules: ['S09', 'S10', 'S11', 'S12', 'S13', 'S14'] },
  { id: 4, name: '手机端', desc: '先把手机端的基础部分做好，再做各个页面，其中下单录入页优先。',
    modules: ['C00', 'C01', 'C02', 'C03', 'C04', 'C05', 'C06', 'C07', 'C08', 'C09', 'C10', 'C11', 'C12'] },
  { id: 5, name: '收尾', desc: '写说明文档、按钮扫描和自动化配置。',
    modules: ['DOCS', 'SCAN', 'HOOKS'] },
];

// /shared 契约种子（真实 /shared 尚未存在时用于契约浏览器）
const SHARED = {
  enums: ['OrderType', 'DocType', 'SettleDirection', 'DiffType', 'OrderStatus'],
  serial: ['PS 采购单', 'PT 采购退货', 'ZS 直送单', 'ZT 直送退货', 'BZ 备注单', 'JS 结算单'],
  errors: ['E401 未登录', 'E403 无权限', 'E404 不存在', 'E409 幂等冲突', 'E422 参数错误'],
  units: ['件', '箱', '袋', '吨', '平方', '米'],
  dto: ['OrderDto', 'LineDto', 'CustomerDto', 'CategoryDto', 'PriceDto', 'PriceChangeLog', 'SettleDto', 'NoteDto', 'AccountDto'],
};

// DTO 流向：谁产出、谁消费（从 KDoc/方案推导的种子）
const DTO_FLOW = {
  'OrderDto': { produces: 'S08', consumes: ['S09', 'S10', 'S11', 'S12', 'S13', 'C06', 'C07', 'C08'] },
  'LineDto': { produces: 'S08', consumes: ['S09', 'S12'] },
  'CustomerDto': { produces: 'S04', consumes: ['S06', 'S08', 'C06'] },
  'CategoryDto': { produces: 'S05', consumes: ['S06', 'S08', 'C11'] },
  'PriceDto': { produces: 'S06', consumes: ['S08', 'C11'] },
  'PriceChangeLog': { produces: 'S06', consumes: ['S14'] },
  'SettleDto': { produces: 'S11', consumes: ['C10', 'C11'] },
  'NoteDto': { produces: 'S10', consumes: ['S12', 'C09'] },
  'AccountDto': { produces: 'S03', consumes: ['S02', 'S13', 'C11'] },
};

const PLAN = {
  contractVersion: '1.0.0',
  projectRoot: WORKSPACE,
  globalRules: GLOBAL_RULES,
  modules: MODULES,
  phases: PHASES,
  shared: SHARED,
  dtoFlow: DTO_FLOW,
};

// ────────────────────────────────────────────────────────────────
// 状态（本地 JSON：验收勾选、约束编辑、github 配置、归档等）
// ────────────────────────────────────────────────────────────────
function defaultState() {
  return {
    projectRoot: WORKSPACE,
    contractVersion: '1.0.0',
    frozenPhases: [],           // 已冻结的阶段 id
    acceptance: {},             // moduleId -> [acceptIndex 已勾选]
    globalRules: GLOBAL_RULES.map((r) => r.text),
    github: { repo: '', token: '', owner: '', name: '' },
    archive: {},                // moduleId -> { taskCard, summary, result, contractVersion, at }
    contractChanges: [],        // 契约变更申请 [{ id, module, dto, reason, status, at }]
  };
}

function loadState() {
  try {
    const raw = readFileSync(STATE_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    return { ...defaultState(), ...parsed };
  } catch {
    return defaultState();
  }
}

let STATE = loadState();

function saveState() {
  mkdirSync(STATE_DIR, { recursive: true });
  writeFileSync(STATE_FILE, JSON.stringify(STATE, null, 2), 'utf8');
}

// ────────────────────────────────────────────────────────────────
// 工具：跑命令（git / gradle）
// ────────────────────────────────────────────────────────────────
function run(cmd, args, cwd) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { cwd, maxBuffer: 10 * 1024 * 1024, windowsHide: true }, (err, stdout, stderr) => {
      if (err) reject(new Error((stderr || '').trim() || err.message));
      else resolve((stdout || '').trim());
    });
  });
}

async function gitDiff() {
  const root = STATE.projectRoot || PLAN.projectRoot;
  try {
    const stat = await run('git', ['diff', '--stat'], root);
    const names = await run('git', ['diff', '--name-only'], root);
    const st = await run('git', ['status', '--porcelain'], root);
    return { ok: true, stat, files: names.split(/\r?\n/).filter(Boolean), status: st };
  } catch (e) {
    return { ok: false, error: String(e && e.message ? e.message : e) };
  }
}

// 越界检查：把 git diff 变动的文件，按模块 write scope 归位，标出越界写
async function scopeCheck() {
  const diff = await gitDiff();
  if (!diff.ok) return diff;
  const byModule = {};
  const outOfScope = [];
  for (const file of diff.files) {
    const norm = file.replace(/\\/g, '/');
    let owner = null;
    for (const m of MODULES) {
      const hit = m.write.some((pat) => {
        const p = pat.replace(/\/\*\*$/, '').replace(/\*\*/g, '');
        return norm.startsWith(p);
      });
      if (hit) { owner = m.id; break; }
    }
    if (owner) (byModule[owner] = byModule[owner] || []).push(file);
    else outOfScope.push(file);
  }
  return { ok: true, stat: diff.stat, byModule, outOfScope };
}

// ────────────────────────────────────────────────────────────────
// 契约浏览器：读取 /shared 目录结构（尚未存在则返回种子）
// ────────────────────────────────────────────────────────────────
async function contractTree() {
  const root = join(STATE.projectRoot || PLAN.projectRoot, 'shared');
  const out = { exists: existsSync(root), tree: [] };
  if (!out.exists) return out;
  // 简单两层列出
  function list(dir, prefix) {
    const items = [];
    for (const name of readdirSync(dir)) {
      const p = join(dir, name);
      if (statSync(p).isDirectory()) items.push({ name, dir: true, path: prefix + name, children: list(p, prefix + name + '/') });
      else items.push({ name, dir: false, path: prefix + name });
    }
    return items;
  }
  out.tree = list(root, 'shared/');
  return out;
}

// ────────────────────────────────────────────────────────────────
// 工具：软运行（不抛错，返回结果对象）
// ────────────────────────────────────────────────────────────────
function runSoft(cmd, args, cwd, env) {
  return new Promise((resolve) => {
    const opts = { cwd, maxBuffer: 20 * 1024 * 1024, windowsHide: true, timeout: 120000 };
    if (env) opts.env = env;
    execFile(cmd, args, opts, (err, stdout, stderr) => {
      resolve({ ok: !err, exit: err ? err.code : 0, out: (stdout || '').trim(), err: (stderr || '').trim(), error: err ? String(err.message) : '' });
    });
  });
}

// ────────────────────────────────────────────────────────────────
// 全局约束 Lint（#11）：扫描代码，检查铁律
// ────────────────────────────────────────────────────────────────
async function lintScan() {
  const root = STATE.projectRoot || PLAN.projectRoot;
  const files = [];
  const skip = new Set(['node_modules', '.git', 'build', '.gradle', '.idea']);
  function walk(dir) {
    if (!existsSync(dir)) return;
    let names;
    try { names = readdirSync(dir); } catch { return; }
    for (const name of names) {
      if (skip.has(name)) continue;
      const p = join(dir, name);
      let st;
      try { st = statSync(p); } catch { continue; }
      if (st.isDirectory()) walk(p);
      else if (/\.(kt|java|xml|sql)$/i.test(name)) files.push(p);
    }
  }
  ['fpk-server', 'android-app', 'shared'].forEach((d) => walk(join(root, d)));
  const violations = [];
  const rules = [
    { id: 'R10', name: 'SQL 出现在 repository 外', hit: (f, t) => /\b(SELECT|INSERT|UPDATE|DELETE)\s/i.test(t) && !/repository/i.test(f) },
    { id: 'R5', name: '客户端计算金额', hit: (f, t) => /android-app/i.test(f) && /\b(amount|money|price|total|金额)\b\s*[+\-*/=]/.test(t) },
    { id: 'R6', name: '打印关键词出现在 Android 端', hit: (f, t) => /android-app/i.test(f) && /\b(print|打印|Print)\b/.test(t) },
    { id: 'R8', name: '单号绕过 SerialPort', hit: (f, t) => /fpk-server/i.test(f) && !/serial/i.test(f) && /\b(serial|单号|orderNo|billNo)\b\s*[=:]/.test(t) },
  ];
  for (const f of files) {
    let txt;
    try { txt = readFileSync(f, 'utf8'); } catch { continue; }
    for (const r of rules) if (r.hit(f, txt)) violations.push({ rule: r.id, name: r.name, file: f.slice(root.length).replace(/^[\\/]/, '').replace(/\\/g, '/') });
  }
  return { ok: true, filesScanned: files.length, violations };
}

// ────────────────────────────────────────────────────────────────
// 验收「一键跑」：gradle 构建（#5）
// ────────────────────────────────────────────────────────────────
async function gradleBuild(moduleId) {
  const root = STATE.projectRoot || PLAN.projectRoot;
  const m = MODULES.find((x) => x.id === moduleId);
  const sub = m ? m.name : moduleId;
  const gradlew = existsSync(join(root, 'gradlew.bat')) ? 'gradlew.bat' : 'gradlew';
  return runSoft(gradlew, [':' + sub + ':build'], root);
}

// ────────────────────────────────────────────────────────────────
// docs 一键生成（#15）
// ────────────────────────────────────────────────────────────────
function genDocs() {
  const byId = {};
  MODULES.forEach((m) => { byId[m.id] = m; });
  const routes = ['# 路由表', ''];
  MODULES.filter((m) => m.layer === 'client').forEach((m) => {
    routes.push('## ' + m.id + ' · ' + m.name);
    routes.push('- 目录：`' + m.dir + '`');
    if (m.ports && m.ports.length) routes.push('- 路由/入口：`' + m.ports.join('`, `') + '`');
    routes.push('');
  });
  const feats = ['# 功能清单', ''];
  PHASES.forEach((ph) => {
    feats.push('## 阶段 ' + ph.id + ' · ' + ph.name);
    feats.push(ph.desc, '');
    ph.modules.forEach((id) => {
      const m = byId[id];
      if (!m) return;
      feats.push('### ' + id + ' · ' + m.name);
      feats.push(m.duty, '');
    });
  });
  return { routes: routes.join('\n'), features: feats.join('\n') };
}

async function writeDocs() {
  const root = STATE.projectRoot || PLAN.projectRoot;
  const docsDir = join(root, 'docs');
  try { mkdirSync(docsDir, { recursive: true }); } catch {}
  const d = genDocs();
  try {
    writeFileSync(join(docsDir, 'routes.md'), d.routes, 'utf8');
    writeFileSync(join(docsDir, 'features.md'), d.features, 'utf8');
    return { ok: true, written: ['docs/routes.md', 'docs/features.md'], ...d };
  } catch (e) {
    return { ok: false, error: String(e && e.message ? e.message : e) };
  }
}

// ────────────────────────────────────────────────────────────────
// GitHub 管理（#16）
// ────────────────────────────────────────────────────────────────
async function githubStatus() {
  const cfg = STATE.github || {};
  const root = STATE.projectRoot || PLAN.projectRoot;
  const gh = await runSoft('gh', ['--version'], root);
  const auth = gh.ok ? await runSoft('gh', ['auth', 'status'], root) : { ok: false, out: '' };
  return { ok: true, configured: !!(cfg.repo && cfg.token), ghAvailable: gh.ok, ghVersion: gh.ok ? gh.out.split('\n')[0] : '', authStatus: auth.ok ? auth.out : '' };
}

async function githubCommit(message) {
  const root = STATE.projectRoot || PLAN.projectRoot;
  const log = [];
  const steps = [['git', ['add', '-A']], ['git', ['commit', '-m', message || 'workbench commit']], ['git', ['push']]];
  for (const [cmd, args] of steps) {
    const r = await runSoft(cmd, args, root);
    log.push('$ ' + cmd + ' ' + args.join(' ') + '\n' + (r.ok ? r.out : (r.err || r.error)));
    if (!r.ok) return { ok: false, error: (r.err || r.error), log: log.join('\n\n') };
  }
  return { ok: true, log: log.join('\n\n') };
}

async function githubRelease(tag, notes) {
  const root = STATE.projectRoot || PLAN.projectRoot;
  const cfg = STATE.github || {};
  const env = cfg.token ? { ...process.env, GH_TOKEN: cfg.token } : process.env;
  const gh = await runSoft('gh', ['release', 'create', tag, '--title', tag, '--notes', notes || ''], root, env);
  if (gh.ok) return { ok: true, method: 'gh', log: gh.out };
  const t = await runSoft('git', ['tag', tag], root);
  if (!t.ok) return { ok: false, error: t.err || t.error, method: 'git' };
  const p = await runSoft('git', ['push', '--tags'], root);
  if (!p.ok) return { ok: false, error: p.err || p.error, method: 'git' };
  return { ok: true, method: 'git', log: (t.out + '\n' + p.out).trim() };
}

async function githubWorkflow(workflow, ref) {
  const cfg = STATE.github || {};
  const env = cfg.token ? { ...process.env, GH_TOKEN: cfg.token } : process.env;
  const args = ['workflow', 'run', workflow];
  if (ref) args.push('--ref', ref);
  const r = await runSoft('gh', args, STATE.projectRoot || PLAN.projectRoot, env);
  return r.ok ? { ok: true, log: r.out } : { ok: false, error: r.err || r.error };
}

// ────────────────────────────────────────────────────────────────
// HTTP 处理
// ────────────────────────────────────────────────────────────────
function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}); }
      catch { resolve({}); }
    });
    req.on('error', () => resolve({}));
  });
}

function sendJson(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

async function handleNode(req, res) {
  const url = new URL(req.url || '/', 'http://x');
  const p = url.pathname.replace(/\/+$/, '');
  const method = req.method || 'GET';
  try {
    if (p === '/bmwb/api/plan' && method === 'GET') return sendJson(res, 200, PLAN);
    if (p === '/bmwb/api/state' && method === 'GET') return sendJson(res, 200, { ok: true, state: STATE });
    if (p === '/bmwb/api/state' && method === 'POST') {
      const body = await readBody(req);
      STATE = { ...STATE, ...body };
      saveState();
      return sendJson(res, 200, { ok: true, state: STATE });
    }
    if (p === '/bmwb/api/git/diff') return sendJson(res, 200, await gitDiff());
    if (p === '/bmwb/api/scope') return sendJson(res, 200, await scopeCheck());
    if (p === '/bmwb/api/contract') return sendJson(res, 200, await contractTree());
    if (p === '/bmwb/api/lint') return sendJson(res, 200, await lintScan());
    if (p === '/bmwb/api/docs/preview') return sendJson(res, 200, genDocs());
    if (p === '/bmwb/api/docs' && method === 'POST') return sendJson(res, 200, await writeDocs());
    if (p === '/bmwb/api/run' && method === 'POST') { const b = await readBody(req); return sendJson(res, 200, await gradleBuild(b.moduleId)); }
    if (p === '/bmwb/api/github/status') return sendJson(res, 200, await githubStatus());
    if (p === '/bmwb/api/github/commit' && method === 'POST') { const b = await readBody(req); return sendJson(res, 200, await githubCommit(b.message)); }
    if (p === '/bmwb/api/github/release' && method === 'POST') { const b = await readBody(req); return sendJson(res, 200, await githubRelease(b.tag, b.notes)); }
    if (p === '/bmwb/api/github/workflow' && method === 'POST') { const b = await readBody(req); return sendJson(res, 200, await githubWorkflow(b.workflow, b.ref)); }
    return sendJson(res, 404, { error: 'not found: ' + p });
  } catch (e) {
    return sendJson(res, 500, { error: String(e && e.message ? e.message : e) });
  }
}

// ────────────────────────────────────────────────────────────────
export function apply(ctx) {
  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() => webCtx.webServer.register({
      kind: 'prefix',
      path: '/bmwb',
      handler: handleNode,
    }), 'bm-workbench: api route');
  });
}
