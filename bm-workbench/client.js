window.__ModuleLoader__.load({
  id: '@local/bm-workbench',
  factory(require) {
    const React = require('react');
    const h = React.createElement;

    // ────────────────────────────────────────────────────────────
    // 工具
    // ────────────────────────────────────────────────────────────
    function fallbackCopy(text) {
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        return ok;
      } catch (e) { return false; }
    }
    function copyText(text) {
      if (navigator && navigator.clipboard && navigator.clipboard.writeText) {
        return navigator.clipboard.writeText(text).then(() => true, () => fallbackCopy(text));
      }
      return Promise.resolve(fallbackCopy(text));
    }
    async function api(path, opts) {
      const r = await fetch('/bmwb' + path, opts);
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
      return j;
    }

    // 中文名映射（界面上只显示大白话）
    const CN_NAME = {
      SHARED: '共享约定', S00: '数据库内核', S01: '部署配置', S02: '登录鉴权', S03: '账号管理',
      S04: '客户档案', S05: '型号库', S06: '价格', S07: '单号', S08: '订单',
      S09: '退单校验', S10: '差异备注', S11: '结算', S12: '打印', S13: '服务网关', S14: '日志审计',
      C00: '网络请求', C01: '登录状态', C02: '通用界面', C03: '应用外壳', C04: '登录页',
      C05: '首页', C06: '下单录入', C07: '历史查询', C08: '今日改单', C09: '差异备注单',
      C10: '结算上传', C11: '管理后台', C12: '设置',
      DOCS: '说明文档', SCAN: '按钮扫描', HOOKS: '自动化',
    };
    function cnName(id) { return CN_NAME[id] || '未命名'; }
    const CN_DIR = { 'shared': '共享约定', 'fpk-server': '服务端', 'android-app': '安卓客户端', 'docs': '文档' };
    function cnDir(m) {
      const seg = (m.dir || '').replace(/^\//, '').split('/')[0] || '';
      return (CN_DIR[seg] || '项目') + ' · ' + cnName(m.id) + '目录';
    }
    const CN_DTO = {
      OrderDto: '订单数据', LineDto: '订单明细', CustomerDto: '客户资料', CategoryDto: '型号资料',
      PriceDto: '价格', PriceChangeLog: '改价记录', SettleDto: '结算单', NoteDto: '差异备注单', AccountDto: '账号资料',
    };
    function cnDto(d) { return CN_DTO[d] || '数据'; }
    function soften(s) {
      return String(s)
        .replace(/client_request_id/g, '重复提交编号')
        .replace(/contract test/gi, '接口测试')
        .replace(/TxRunner/g, '统一事务')
        .replace(/\bSQL\b/g, '数据库语句')
        .replace(/\bPort\b/g, '接口')
        .replace(/\bDTO\b/g, '数据')
        .replace(/\brepository\b/g, '数据仓库层')
        .replace(/:memory:/g, '内存数据库')
        .replace(/\bEntity\b/g, '实体')
        .replace(/@Deprecated/g, '标记为废弃');
    }
    const ST = {
      todo: { label: '未开始', color: '#6b7280' },
      doing: { label: '进行中', color: '#2563eb' },
      review: { label: '待审核', color: '#d97706' },
      done: { label: '已完成', color: '#16a34a' },
      issue: { label: '有问题', color: '#dc2626' },
    };
    function stOf(data, id) {
      const s = (data.moduleStatus || {})[id];
      return ST[s] ? s : 'todo';
    }
    function stNext(s) {
      const order = ['todo', 'doing', 'review', 'done', 'issue'];
      return order[(Math.max(0, order.indexOf(s)) + 1) % order.length];
    }
    function StatusChip(props) {
      const s = ST[props.status] || ST.todo;
      return h('span', { className: 'bm-chip', style: { background: s.color, color: '#fff', borderColor: s.color } }, s.label);
    }
    function buildTaskCardCn(plan, data, m) {
      const rules = data.globalRules && data.globalRules.length ? data.globalRules : (plan.globalRules || []).map((r) => r.text);
      const L = [];
      L.push('【派活说明 · ' + cnName(m.id) + '】');
      L.push('');
      L.push('一、这个模块干什么');
      L.push(soften(m.duty));
      L.push('');
      L.push('二、只允许改动这些地方');
      L.push('- ' + cnDir(m));
      L.push('');
      L.push('三、不许做的事');
      (m.forbidden || []).forEach((f) => L.push('- ' + soften(f)));
      L.push('');
      L.push('四、验收标准');
      (m.accept || []).forEach((a) => L.push('- ' + soften(a)));
      L.push('');
      L.push('五、全体都要守的规矩');
      rules.forEach((r, i) => L.push((i + 1) + '. ' + soften(r)));
      return L.join('\n');
    }

    // ────────────────────────────────────────────────────────────
    // 任务卡生成
    // ────────────────────────────────────────────────────────────
    const TEMPLATES = [
      { id: 'server', name: '服务端模块模板', note: '本模板强调：Port 接口只增不改；返回 DTO 不返回 Entity；跨模块事务走 db-core 的 TxRunner；SQL 只在 repository 内；表归属本模块。' },
      { id: 'side', name: '旁链模块模板', note: '本模板强调：只通过 OrderPort 读订单、禁止直连 order 表；不改原单；事件订阅（PriceChanged）解耦；规则独立可单独升级。' },
      { id: 'core', name: '客户端 core 模板', note: '本模板强调：只做通用能力（网络/token/组件），禁止业务逻辑；token 判断只在 core-session 一处；组件可复用。' },
      { id: 'feature', name: '客户端 feature 模板', note: '本模板强调：复用录入页壳/组件；金额、单号、批号一律服务端产出、客户端只展示；只调聚合接口，禁止本地拼 SQL/直连。' },
    ];

    function buildTaskCard(plan, data, m, templateNote) {
      const byId = {};
      (plan.modules || []).forEach((x) => { byId[x.id] = x; });
      const rules = data.globalRules && data.globalRules.length ? data.globalRules : (plan.globalRules || []).map((r) => r.text);
      const L = [];
      L.push('# 模块任务卡 · ' + m.id + ' ' + m.name);
      L.push('');
      L.push('> 契约版本 contractVersion = ' + (data.contractVersion || plan.contractVersion));
      L.push('');
      L.push('## 职责');
      L.push(m.duty);
      L.push('');
      if (templateNote) {
        L.push('## 模板要求');
        L.push(templateNote);
        L.push('');
      }
      L.push('## 写入范围（只许写这些目录）');
      (m.write || []).forEach((w) => L.push('- `' + w + '`'));
      L.push('');
      L.push('## 本模块实现的 Port');
      (m.ports || []).forEach((p) => L.push('- `' + p + '`'));
      L.push('');
      L.push('## 依赖（调用的 Port）');
      (m.calls || []).forEach((id) => {
        const d = byId[id];
        L.push('- `' + id + '`' + (d ? ' ' + d.name : '') + (d && d.ports && d.ports.length ? ' → ' + d.ports.join('、') : ''));
      });
      if (!(m.calls || []).length) L.push('- 无');
      L.push('');
      L.push('## 硬边界（禁止）');
      (m.forbidden || []).forEach((f) => L.push('- ' + f));
      L.push('');
      L.push('## 验收标准');
      (m.accept || []).forEach((a) => L.push('- [ ] ' + a));
      L.push('');
      L.push('## 全局约束（铁律）');
      rules.forEach((r, i) => L.push((i + 1) + '. ' + r));
      L.push('');
      return L.join('\n');
    }

    function buildModuleMd(plan, m) {
      const L = [];
      L.push('# ' + m.id + ' · ' + m.name);
      L.push('');
      L.push('## 职责');
      L.push(m.duty);
      L.push('');
      L.push('## 对外接口（Port）');
      (m.ports || []).forEach((p) => L.push('- `' + p + '`'));
      if (!(m.ports || []).length) L.push('- 无');
      L.push('');
      L.push('## 依赖');
      (m.calls || []).forEach((d) => L.push('- ' + d));
      if (!(m.calls || []).length) L.push('- 无');
      L.push('');
      L.push('## 表归属');
      L.push('- ' + m.id + ' 拥有自己的表（若有）；跨模块只能走 Port，禁止直连表。');
      L.push('');
      L.push('## 写入范围');
      (m.write || []).forEach((w) => L.push('- `' + w + '`'));
      L.push('');
      L.push('## 变更记录');
      L.push('- ' + new Date().toISOString().slice(0, 10) + ' 初始骨架');
      L.push('');
      return L.join('\n');
    }

    function gateWarning(plan, data, m) {
      if (!m || !m.phase || m.phase <= 1) return null;
      const prev = (plan.phases || []).find((p) => p.id === m.phase - 1);
      if (!prev) return null;
      const frozen = new Set(data.frozenPhases || []);
      if (frozen.has(prev.id)) return null;
      const done = data.acceptance || {};
      const byId = {};
      (plan.modules || []).forEach((x) => { byId[x.id] = x; });
      const prevDone = prev.modules.every((id) => { const mm = byId[id]; return mm && (done[id] || []).length === (mm.accept || []).length; });
      if (prevDone) return null;
      return '阶段闸门未通过：阶段 ' + prev.id + '（' + prev.name + '）尚未冻结，按规则禁止下发阶段 ' + m.phase + ' 的任务卡。';
    }

    // ────────────────────────────────────────────────────────────
    // 样式
    // ────────────────────────────────────────────────────────────
    const CSS = `
.bm-root{height:100%;overflow:auto;background:var(--dsw-alias-bg-base);color:var(--dsw-alias-label-primary, #1f2937);font-size:13px;line-height:1.55}
.bm-inner{max-width:960px;margin:0 auto;padding:20px 24px 56px}
.bm-head{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;margin-bottom:14px}
.bm-title{font-size:19px;font-weight:600;margin:0}
.bm-sub{color:var(--dsw-alias-label-secondary, #6b7280);font-size:12px}
.bm-tabs{display:flex;gap:4px;flex-wrap:wrap;border-bottom:1px solid var(--dsw-alias-border-l1);margin-bottom:16px;padding-bottom:0}
.bm-tab{background:none;border:none;border-bottom:2px solid transparent;padding:8px 12px;font-size:13px;color:var(--dsw-alias-label-secondary, #6b7280);cursor:pointer;white-space:nowrap}
.bm-tab:hover{color:var(--dsw-alias-label-primary, #1f2937)}
.bm-tab.bm-active{color:var(--dsw-alias-label-primary, #1f2937);border-bottom-color:var(--dsw-alias-brand-primary, #2563eb);font-weight:600}
.bm-card{border:1px solid var(--dsw-alias-border-l1);border-radius:10px;margin-bottom:12px;overflow:hidden}
.bm-card-h{background:var(--dsw-alias-bg-layer-1);padding:10px 14px;font-weight:600;display:flex;align-items:center;gap:8px}
.bm-card-b{padding:12px 14px}
.bm-chip{display:inline-flex;align-items:center;border-radius:999px;border:1px solid var(--dsw-alias-border-l1);background:var(--dsw-alias-bg-layer-1);padding:1px 9px;font-size:11px;color:var(--dsw-alias-label-secondary, #6b7280);margin:2px 4px 2px 0}
.bm-code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;background:var(--dsw-alias-bg-layer-2);border-radius:6px;padding:1px 6px;color:var(--dsw-alias-brand-primary, #2563eb)}
.bm-btn{display:inline-flex;align-items:center;gap:6px;border-radius:8px;border:1px solid var(--dsw-alias-border-l2, #d1d5db);background:transparent;color:var(--dsw-alias-label-primary, #1f2937);padding:5px 11px;font-size:12px;cursor:pointer}
.bm-btn:hover{background:var(--dsw-alias-bg-layer-2, #f3f4f6)}
.bm-btn-p{background:#2563eb;border-color:#2563eb;color:#ffffff}
.bm-btn-p:hover{opacity:.9;background:#1d4ed8}
.bm-row{display:flex;align-items:center;gap:8px;padding:7px 12px;border-bottom:1px solid var(--dsw-alias-border-l1);flex-wrap:wrap}
.bm-row:last-child{border-bottom:none}
.bm-row:hover{background:var(--dsw-alias-bg-layer-1)}
.bm-grow{flex:1;min-width:120px}
.bm-muted{color:var(--dsw-alias-label-secondary, #6b7280);font-size:12px}
.bm-field-label{font-size:11px;font-weight:600;color:var(--dsw-alias-label-secondary, #6b7280);margin:8px 0 3px;text-transform:uppercase;letter-spacing:.03em}
.bm-red{color:var(--dsw-alias-state-error-primary, #dc2626)}
.bm-green{color:var(--dsw-alias-state-success-primary, #16a34a)}
.bm-amber{color:var(--dsw-alias-state-warn-primary, #d97706)}
.bm-pre{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;line-height:1.6;white-space:pre-wrap;word-break:break-word;background:var(--dsw-alias-bg-layer-1);border:1px solid var(--dsw-alias-border-l1);border-radius:8px;padding:12px;margin:0;max-height:60vh;overflow:auto}
.bm-select{background:var(--dsw-alias-bg-layer-1);color:var(--dsw-alias-label-primary, #1f2937);border:1px solid var(--dsw-alias-border-l2);border-radius:8px;padding:5px 8px;font-size:13px}
.bm-input{width:100%;box-sizing:border-box;background:var(--dsw-alias-bg-layer-1);color:var(--dsw-alias-label-primary, #1f2937);border:1px solid var(--dsw-alias-border-l2);border-radius:8px;padding:6px 8px;font-size:13px}
.bm-textarea{width:100%;box-sizing:border-box;background:var(--dsw-alias-bg-layer-1);color:var(--dsw-alias-label-primary, #1f2937);border:1px solid var(--dsw-alias-border-l2);border-radius:8px;padding:8px;font-size:12px;font-family:inherit;min-height:80px;resize:vertical}
.bm-check{accent-color:var(--dsw-alias-brand-primary, #2563eb);width:15px;height:15px}
.bm-toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:var(--dsw-alias-bg-overlay);border:1px solid var(--dsw-alias-border-l2);color:var(--dsw-alias-label-primary, #1f2937);padding:8px 16px;border-radius:8px;box-shadow:0 6px 24px rgba(0,0,0,.2);z-index:1200;font-size:13px}
.bm-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:10px}
.bm-scope-tag{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px}
`;

    // ────────────────────────────────────────────────────────────
    // 小组件
    // ────────────────────────────────────────────────────────────
    function PanelIcon(props) {
      const s = props && props.size ? props.size : 18;
      return h('svg', { viewBox: '0 0 24 24', width: s, height: s, fill: 'currentColor', 'aria-hidden': true },
        h('rect', { x: 4, y: 4, width: 9, height: 4, rx: 1 }),
        h('rect', { x: 14, y: 4, width: 6, height: 4, rx: 1 }),
        h('rect', { x: 4, y: 9.5, width: 5.5, height: 4, rx: 1 }),
        h('rect', { x: 10.5, y: 9.5, width: 9.5, height: 4, rx: 1 }),
        h('rect', { x: 4, y: 15, width: 9, height: 4, rx: 1 }),
        h('rect', { x: 14, y: 15, width: 6, height: 4, rx: 1 })
      );
    }

    function Card(props) {
      return h('div', { className: 'bm-card' },
        props.title ? h('div', { className: 'bm-card-h' }, props.title) : null,
        h('div', { className: 'bm-card-b' }, props.children));
    }

    // ────────────────────────────────────────────────────────────
    // 总览 / 阶段
    // ────────────────────────────────────────────────────────────
    class Overview extends React.Component {
      render() {
        const { plan, data, onSave, onDispatchAgent } = this.props;
        const setStatus = (id, s) => onSave({ moduleStatus: { ...(data.moduleStatus || {}), [id]: s } });
        const byId = {};
        (plan.modules || []).forEach((m) => { byId[m.id] = m; });
        const frozenSet = new Set(data.frozenPhases || []);
        const done = data.acceptance || {};
        const phaseDone = (ph) => ph.modules.every((id) => { const m = byId[id]; return m && (done[id] || []).length === (m.accept || []).length; });
        const isFrozen = (ph) => frozenSet.has(ph.id) || phaseDone(ph);
        const toggleFreeze = (ph) => {
          const cur = new Set(data.frozenPhases || []);
          if (cur.has(ph.id)) cur.delete(ph.id); else cur.add(ph.id);
          onSave({ frozenPhases: [...cur] });
        };
        return h('div', null,
          h('p', { className: 'bm-sub' }, '当前约定版本：第 ' + (data.contractVersion || plan.contractVersion) + ' 版'),
          (plan.phases || []).map((ph) => {
            const doneCount = ph.modules.filter((id) => {
              const m = byId[id]; return m && (done[id] || []).length === (m.accept || []).length;
            }).length;
            return h('div', { className: 'bm-card', key: ph.id },
              h('div', { className: 'bm-card-h' },
                h('span', null, '阶段 ' + ph.id + ' · ' + ph.name),
                h('span', { className: 'bm-chip' }, ph.modules.length + ' 个模块'),
                h('span', { className: 'bm-chip' }, doneCount + '/' + ph.modules.length + ' 完成'),
                isFrozen(ph) ? h('span', { className: 'bm-chip bm-amber' }, '已冻结') : null,
                h('span', { style: { marginLeft: 'auto' } },
                  h('button', { className: 'bm-btn', onClick: () => toggleFreeze(ph) }, isFrozen(ph) ? '解冻' : '冻结'))),
              h('div', { className: 'bm-card-b' },
                h('div', { className: 'bm-muted', style: { marginBottom: 8 } }, ph.desc),
                ph.modules.map((id) => {
                  const m = byId[id];
                  if (!m) return null;
                  const deps = (m.calls || []).map((d) => cnName(d)).join('、');
                  return h('div', { className: 'bm-row', key: id },
                    h('span', { className: 'bm-grow' }, h('b', null, cnName(id)), '　', h('span', { className: 'bm-muted' }, m.duty)),
                    h(StatusChip, { status: stOf(data, id) }),
                    h('span', { className: 'bm-muted' }, deps ? '要先做：' + deps : '不依赖别人'),
                    h('button', { className: 'bm-btn', onClick: () => setStatus(id, stNext(stOf(data, id))) }, '改状态'),
                    h('button', { className: 'bm-btn bm-btn-p', onClick: () => { setStatus(id, 'doing'); const t = buildTaskCard(plan, data, m); if (onDispatchAgent) onDispatchAgent(t); else copyText(t); } }, '派人干'));
                })));
          }));
      }
    }

    // ────────────────────────────────────────────────────────────
    // 任务卡生成
    // ────────────────────────────────────────────────────────────
    class TaskCards extends React.Component {
      constructor(props) { super(props); this.state = { sel: '', text: '', tpl: '' }; }
      regen(id, tpl) {
        const m = (this.props.plan.modules || []).find((x) => x.id === id);
        const t = TEMPLATES.find((x) => x.id === tpl);
        this.setState({ sel: id, tpl, text: m ? buildTaskCard(this.props.plan, this.props.data, m, t ? t.note : '') : '' });
      }
      pick(id) { this.regen(id, this.state.tpl); }
      setTpl(tpl) { this.regen(this.state.sel, tpl); }
      render() {
        const { plan, data, toast } = this.props;
        const byId = {};
        (plan.modules || []).forEach((m) => { byId[m.id] = m; });
        const groups = [
          ['共享约定', ['SHARED']],
          ['服务端', (plan.modules || []).filter((m) => m.layer === 'server').map((m) => m.id)],
          ['安卓客户端', (plan.modules || []).filter((m) => m.layer === 'client').map((m) => m.id)],
          ['收尾', (plan.modules || []).filter((m) => m.layer === 'docs').map((m) => m.id)],
        ];
        const opts = groups.map((g) => {
          const children = g[1].map((id) => h('option', { key: id, value: id }, cnName(id)));
          return h('optgroup', { key: g[0], label: g[0] }, children);
        });
        const tplOpts = TEMPLATES.map((t) => h('option', { key: t.id, value: t.id }, t.name));
        const selM = byId[this.state.sel];
        const warn = gateWarning(plan, data, selM);
        const toolbar = h('div', { style: { marginBottom: 10, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' } },
          h('span', { className: 'bm-muted' }, '选模块：'),
          h('select', { className: 'bm-select', value: this.state.sel, onChange: (e) => this.pick(e.target.value) },
            h('option', { value: '' }, '—'),
            opts),
          selM ? h(StatusChip, { status: stOf(data, selM.id) }) : null,
          h('span', { className: 'bm-muted' }, '用哪套说明：'),
          h('select', { className: 'bm-select', value: this.state.tpl, onChange: (e) => this.setTpl(e.target.value) },
            h('option', { value: '' }, '不要'),
            tplOpts),
          this.state.text ? h('button', { className: 'bm-btn bm-btn-p', onClick: () => { if (this.props.onSave && selM) this.props.onSave({ moduleStatus: { ...(data.moduleStatus || {}), [selM.id]: 'doing' } }); const t = this.state.text; if (this.props.onDispatchAgent) this.props.onDispatchAgent(t); else copyText(t); toast('已标为进行中，说明已复制并新开对话'); } }, '派人干这活') : null,
          this.state.text ? h('button', { className: 'bm-btn', onClick: () => { copyText(this.state.text); toast('说明已复制'); } }, '复制给机器人的说明') : null,
          this.state.text ? h('button', { className: 'bm-btn', onClick: () => { copyText(buildModuleMd(plan, selM)); toast('模块说明已复制'); } }, '生成模块说明') : null);
        const warnBanner = warn ? h('div', { className: 'bm-card', style: { marginBottom: 10 } }, h('div', { className: 'bm-card-b bm-red' }, warn)) : null;
        const body = this.state.text ? h('pre', { className: 'bm-pre' }, buildTaskCardCn(plan, data, selM)) : h('p', { className: 'bm-muted' }, '选一个模块，看看要干什么活、能改哪里、不许做什么。');
        const hint = h('p', { className: 'bm-muted' }, '用法：选模块 → 点「派人干这活」→ 自动复制说明并新开一个对话 → 在新对话里粘贴（Ctrl+V）回车 → 机器人开干 → 回来点「审核通过并回传」。');
        return h('div', null, hint, toolbar, warnBanner, body);
      }
    }

    // ────────────────────────────────────────────────────────────
    // 全局约束清单
    // ────────────────────────────────────────────────────────────
    class Rules extends React.Component {
      constructor(props) { super(props); this.state = { rules: [] }; }
      componentDidMount() { this.sync(this.props); }
      componentDidUpdate(prev) { if (prev.data !== this.props.data) this.sync(this.props); }
      sync(props) {
        const rules = props.data.globalRules && props.data.globalRules.length ? props.data.globalRules : (props.plan.globalRules || []).map((r) => r.text);
        if (JSON.stringify(rules) !== JSON.stringify(this.state.rules)) this.setState({ rules });
      }
      set(i, v) { const rules = this.state.rules.slice(); rules[i] = v; this.setState({ rules }); }
      render() {
        const { onSave, toast } = this.props;
        return h('div', null,
          h('p', { className: 'bm-muted' }, '10 条铁律集中维护，改动一次、所有后续任务卡自动引用。'),
          this.state.rules.map((r, i) => h('div', { className: 'bm-row', key: i },
            h('span', { className: 'bm-code' }, String(i + 1)),
            h('input', { className: 'bm-input bm-grow', value: r, onChange: (e) => this.set(i, e.target.value) }))),
          h('div', { style: { marginTop: 10, display: 'flex', gap: 8 } },
            h('button', { className: 'bm-btn bm-btn-p', onClick: () => { onSave({ globalRules: this.state.rules }); toast('已保存约束清单'); } }, '保存约束清单')));
      }
    }

    // ────────────────────────────────────────────────────────────
    // 写入范围看板 + git diff 越界检查
    // ────────────────────────────────────────────────────────────
    class ScopeBoard extends React.Component {
      constructor(props) { super(props); this.state = { result: null, loading: false }; }
      async check() {
        this.setState({ loading: true });
        try { this.setState({ result: await api('/api/scope') }); }
        catch (e) { this.setState({ result: { ok: false, error: String(e.message || e) } }); }
        this.setState({ loading: false });
      }
      render() {
        const { plan } = this.props;
        const r = this.state.result;
        const scopeOf = {};
        if (r && r.ok && r.byModule) {
          Object.keys(r.byModule).forEach((id) => { scopeOf[id] = r.byModule[id]; });
        }

        const checkBar = h('div', { style: { marginBottom: 10, display: 'flex', gap: 8, alignItems: 'center' } },
          h('button', { className: 'bm-btn bm-btn-p', onClick: () => this.check(), disabled: this.state.loading },
            this.state.loading ? '检查中…' : '检查有没有乱改文件'),
          h('span', { className: 'bm-muted' }, '干完活点一下，谁乱改了范围外的文件会标红。'));

        let diffCard = null;
        if (r && !r.ok) {
          diffCard = h('div', { className: 'bm-card' }, h('div', { className: 'bm-card-b bm-red' }, '检查失败：' + (r.error || '未知')));
        } else if (r && r.ok) {
          const outOfScope = (r.outOfScope || []).map((f, i) => h('div', { key: i, className: 'bm-red bm-scope-tag' }, f));
          diffCard = h('div', { className: 'bm-card' },
            h('div', { className: 'bm-card-h' }, '这次改了哪些文件'),
            h('div', { className: 'bm-card-b' },
              r.stat ? h('pre', { className: 'bm-pre' }, r.stat) : h('span', { className: 'bm-muted' }, '还没改过'),
              (r.outOfScope || []).length ? h('div', { style: { marginTop: 10 } },
                h('div', { className: 'bm-red', style: { fontWeight: 600 } }, '乱改了（不在任何模块的允许范围内）：'),
                outOfScope) : null));
        }

        const moduleCards = (plan.modules || []).map((m) => {
          const files = scopeOf[m.id] || [];
          return h('div', { className: 'bm-card', key: m.id },
            h('div', { className: 'bm-card-h' },
              h('span', { className: 'bm-grow' }, h('b', null, cnName(m.id))),
              files.length ? h('span', { className: 'bm-chip bm-green' }, files.length + ' 处改动') : null),
            h('div', { className: 'bm-card-b' },
              h('div', { className: 'bm-muted' }, '允许改：' + cnDir(m))));
        });

        return h('div', null, checkBar, diffCard,
          h('div', { className: 'bm-grid', style: { marginTop: 12 } }, moduleCards));
      }
    }

    // ────────────────────────────────────────────────────────────
    // 依赖图
    // ────────────────────────────────────────────────────────────
    class Graph extends React.Component {
      constructor(props) { super(props); this.state = { hover: null }; }
      render() {
        const plan = this.props.plan;
        const byId = {};
        (plan.modules || []).forEach((m) => { byId[m.id] = m; });
        const phases = (plan.phases || []).slice().sort((a, b) => a.id - b.id);
        const pos = {};
        const colW = 172, rowH = 46, padX = 70, padY = 70;
        let maxRows = 1;
        phases.forEach((ph, ci) => {
          ph.modules.forEach((id, ri) => {
            const m = byId[id];
            if (!m) return;
            pos[id] = { x: padX + ci * colW, y: padY + ri * rowH, m };
            maxRows = Math.max(maxRows, ri + 1);
          });
        });
        const edges = [];
        (plan.modules || []).forEach((m) => { (m.calls || []).forEach((d) => { if (pos[d] && pos[m.id]) edges.push({ from: d, to: m.id }); }); });

        // 依赖反向图（下游）
        const dependents = {};
        edges.forEach((e) => { (dependents[e.from] = dependents[e.from] || []).push(e.to); });
        function ancestors(id) { const s = new Set(); const walk = (x) => { (byId[x] && byId[x].calls || []).forEach((d) => { if (!s.has(d)) { s.add(d); walk(d); } }); }; walk(id); return s; }
        function descendants(id) { const s = new Set(); const walk = (x) => { (dependents[x] || []).forEach((d) => { if (!s.has(d)) { s.add(d); walk(d); } }); }; walk(id); return s; }

        const hover = this.state.hover;
        const up = hover ? ancestors(hover) : new Set();
        const down = hover ? descendants(hover) : new Set();

        const W = padX + phases.length * colW + 60;
        const H = padY + maxRows * rowH + 40;

        const edgeEls = edges.map((e, i) => {
          const a = pos[e.from], b = pos[e.to];
          let stroke = 'var(--dsw-alias-border-l2)', w = 1;
          if (hover && up.has(e.from) && (up.has(e.to) || e.to === hover)) { stroke = 'var(--dsw-alias-state-success-primary, #16a34a)'; w = 2; }
          else if (hover && down.has(e.to) && (down.has(e.from) || e.from === hover)) { stroke = 'var(--dsw-alias-state-warn-primary, #d97706)'; w = 2; }
          else if (hover) { stroke = 'rgba(128,128,128,.15)'; }
          return h('line', { key: i, x1: a.x + 150, y1: a.y + 17, x2: b.x, y2: b.y + 17, stroke: stroke, strokeWidth: w });
        });
        const nodeEls = Object.keys(pos).map((id) => {
          const n = pos[id];
          let fill = 'var(--dsw-alias-bg-layer-2)';
          let stroke = 'var(--dsw-alias-border-l2)';
          let opacity = 1;
          const hot = hover && (id === hover || up.has(id) || down.has(id));
          if (hover) {
            if (id === hover) { fill = 'var(--dsw-alias-brand-primary, #2563eb)'; stroke = 'var(--dsw-alias-brand-primary, #2563eb)'; }
            else if (up.has(id)) { fill = 'var(--dsw-alias-state-success-primary, #16a34a)'; stroke = 'var(--dsw-alias-state-success-primary, #16a34a)'; }
            else if (down.has(id)) { fill = 'var(--dsw-alias-state-warn-primary, #d97706)'; stroke = 'var(--dsw-alias-state-warn-primary, #d97706)'; }
            else { opacity = 0.35; }
          }
          const tcol = hot ? '#fff' : 'var(--dsw-alias-label-primary, #1f2937)';
          const scol = hot ? '#fff' : 'var(--dsw-alias-label-secondary, #6b7280)';
          return h('g', { key: id, onMouseEnter: () => this.setState({ hover: id }), onMouseLeave: () => this.setState({ hover: null }), style: { cursor: 'pointer', opacity: opacity } },
            h('rect', { x: n.x, y: n.y, width: 150, height: 34, rx: 7, fill: fill, stroke: stroke, strokeWidth: 1.2 }),
            h('text', { x: n.x + 12, y: n.y + 22, fill: tcol, fontSize: 12 }, cnName(id)));
        });
        const phEls = phases.map((ph, ci) => h('text', { key: 'ph' + ph.id, x: padX + ci * colW, y: 34, fill: 'var(--dsw-alias-label-secondary, #6b7280)', fontSize: 12, fontWeight: 600 }, '第 ' + ph.id + ' 阶段 · ' + ph.name));

        return h('div', null,
          h('p', { className: 'bm-muted' }, '鼠标放上去：绿色是它必须先做完的，橙色是等它做完才能开工的。'),
          h('div', { style: { overflow: 'auto', border: '1px solid var(--dsw-alias-border-l1)', borderRadius: 10, background: 'var(--dsw-alias-bg-layer-1)' } },
            h('svg', { viewBox: '0 0 ' + W + ' ' + H, width: '100%', style: { minWidth: W, display: 'block' } },
              edgeEls,
              nodeEls,
              phEls)));
      }
    }

    // ────────────────────────────────────────────────────────────
    // 验收清单
    // ────────────────────────────────────────────────────────────
    class Acceptance extends React.Component {
      constructor(props) { super(props); this.state = { build: null }; }
      toggle(id, idx) {
        const acc = this.props.data.acceptance || {};
        const cur = acc[id] || [];
        const next = cur.includes(idx) ? cur.filter((x) => x !== idx) : cur.concat(idx);
        this.props.onSave({ acceptance: { ...acc, [id]: next } });
      }
      async runBuild(id) {
        this.setState({ build: { id, loading: true, r: null } });
        try { this.setState({ build: { id, loading: false, r: await api('/api/run', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ moduleId: id }) }) } }); }
        catch (e) { this.setState({ build: { id, loading: false, r: { ok: false, error: String(e.message || e) } } }); }
      }
      pass(m) {
        const { plan, data, onSave, toast } = this.props;
        const acc = data.acceptance || {};
        const rec = { taskCard: buildTaskCard(plan, data, m), summary: '', result: (acc[m.id] || []).length + '/' + (m.accept || []).length, contractVersion: data.contractVersion || plan.contractVersion, at: new Date().toISOString() };
        onSave({ moduleStatus: { ...(data.moduleStatus || {}), [m.id]: 'done' }, archive: { ...(data.archive || {}), [m.id]: rec } });
        if (toast) toast('已审核通过，结果已回传到工作台存档');
      }
      render() {
        const { plan, data } = this.props;
        const acc = data.acceptance || {};
        const build = this.state.build;
        return h('div', null,
          h('p', { className: 'bm-muted' }, '每个模块打勾验收，或点「试编译」看看能不能编译通过（要先有工程）。'),
          (plan.modules || []).map((m) => {
            const cur = acc[m.id] || [];
            const all = (m.accept || []).length > 0 && cur.length === (m.accept || []).length;
            const b = build && build.id === m.id ? build : null;
            const buildOut = b ? (b.loading ? h('div', { className: 'bm-muted' }, '编译中…')
              : h('pre', { className: 'bm-pre', style: { maxHeight: 160, marginTop: 8 } }, (b.r && (b.r.out || b.r.err || b.r.error)) || JSON.stringify(b.r))) : null;
            return h('div', { className: 'bm-card', key: m.id },
              h('div', { className: 'bm-card-h' },
                h('span', { className: 'bm-grow' }, h('b', null, cnName(m.id))),
                h(StatusChip, { status: stOf(data, m.id) }),
                all ? h('span', { className: 'bm-chip bm-green' }, '✓ 验收勾完') : null,
                h('button', { className: 'bm-btn bm-btn-p', onClick: () => this.pass(m), disabled: stOf(data, m.id) === 'done' }, '审核通过并回传'),
                h('button', { className: 'bm-btn', onClick: () => this.runBuild(m.id), disabled: b && b.loading }, '试编译')),
              h('div', { className: 'bm-card-b' },
                (m.accept || []).map((a, i) => h('label', { key: i, className: 'bm-row', style: { cursor: 'pointer' } },
                  h('input', { type: 'checkbox', className: 'bm-check', checked: cur.includes(i), onChange: () => this.toggle(m.id, i) }),
                  h('span', { className: 'bm-grow' }, a))),
                buildOut));
          }));
      }
    }

    // ────────────────────────────────────────────────────────────
    // 主面板
    // ────────────────────────────────────────────────────────────
    const TABS = [
      ['overview', '总览'],
      ['contract', '约定一览'],
      ['version', '约定版本'],
      ['tasks', '派活'],
      ['rules', '规矩'],
      ['scope', '改动范围'],
      ['graph', '先后关系'],
      ['conflict', '同时开工'],
      ['archive', '存档'],
      ['accept', '验收'],
      ['lint', '代码体检'],
      ['docs', '文档生成'],
      ['github', '代码仓库'],
    ];

    class Workbench extends React.Component {
      constructor(props) {
        super(props);
        this.state = { plan: null, data: null, tab: 'overview', error: null, toast: null };
        this._toastTimer = null;
      }
      componentDidMount() { this.load(); }
      componentWillUnmount() { if (this._toastTimer) clearTimeout(this._toastTimer); }
      toast(msg) {
        this.setState({ toast: msg });
        if (this._toastTimer) clearTimeout(this._toastTimer);
        this._toastTimer = setTimeout(() => this.setState({ toast: null }), 2200);
      }
      async load() {
        try {
          const p = await api('/api/plan');
          const s = await api('/api/state');
          this.setState({ plan: p, data: s.state || {} });
        } catch (e) {
          this.setState({ error: String(e.message || e) });
        }
      }
      async save(patch) {
        const data = { ...(this.state.data || {}), ...patch };
        this.setState({ data });
        try { const r = await api('/api/state', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(patch) }); if (r.state) this.setState({ data: r.state }); }
        catch (e) { this.toast('保存失败：' + (e.message || e)); }
      }
      render() {
        const { plan, data, tab, error, toast } = this.state;
        if (error) {
          return h('div', { className: 'bm-root' },
            h('div', { className: 'bm-inner' }, h('div', { className: 'bm-red', style: { whiteSpace: 'pre-wrap' } }, '工作台加载出错：\n' + error)));
        }
        if (!plan) return h('div', { className: 'bm-root' }, h('div', { className: 'bm-inner' }, h('span', { className: 'bm-muted' }, '加载中…')));
        const d = data || { globalRules: (plan.globalRules || []).map((r) => r.text), acceptance: {}, frozenPhases: [], contractVersion: plan.contractVersion, projectRoot: plan.projectRoot, github: {} };
        let content = null;
        if (tab === 'overview') content = h(Overview, { plan, data: d, onSave: (p) => this.save(p), onDispatchAgent: this.props.onDispatchAgent });
        else if (tab === 'contract') content = h(ContractExplorer, { plan, data: d });
        else if (tab === 'version') content = h(ContractVersion, { plan, data: d, onSave: (p) => this.save(p), toast: (m) => this.toast(m) });
        else if (tab === 'tasks') content = h(TaskCards, { plan, data: d, onSave: (p) => this.save(p), toast: (m) => this.toast(m), onDispatchAgent: this.props.onDispatchAgent });
        else if (tab === 'rules') content = h(Rules, { plan, data: d, onSave: (p) => this.save(p), toast: (m) => this.toast(m) });
        else if (tab === 'scope') content = h(ScopeBoard, { plan, data: d });
        else if (tab === 'graph') content = h(Graph, { plan });
        else if (tab === 'conflict') content = h(ConflictPrecheck, { plan, data: d });
        else if (tab === 'archive') content = h(Archive, { plan, data: d, onSave: (p) => this.save(p), toast: (m) => this.toast(m) });
        else if (tab === 'accept') content = h(Acceptance, { plan, data: d, onSave: (p) => this.save(p), toast: (m) => this.toast(m) });
        else if (tab === 'lint') content = h(LintPanel, { plan, data: d });
        else if (tab === 'docs') content = h(DocsPanel, { plan, data: d });
        else if (tab === 'github') content = h(GithubPanel, { plan, data: d, onSave: (p) => this.save(p), toast: (m) => this.toast(m) });

        const totalMods = (plan.modules || []).length;
        const doneCount = (plan.modules || []).filter((m) => stOf(d, m.id) === 'done').length;
        const pct = totalMods ? Math.round((doneCount / totalMods) * 100) : 0;
        const progressBar = h('div', { style: { marginBottom: 8 } },
          h('div', { className: 'bm-muted', style: { marginBottom: 4 } }, '总进度：已完成 ' + doneCount + ' / 共 ' + totalMods + ' 项（' + pct + '%）'),
          h('div', { style: { height: 8, background: '#e5e7eb', borderRadius: 4, overflow: 'hidden' } },
            h('div', { style: { width: pct + '%', height: '100%', background: '#16a34a' } })));
        const statusRow = h('div', { style: { marginBottom: 10, display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'center' } },
          Object.keys(ST).map((k) => {
            const n = (plan.modules || []).filter((m) => stOf(d, m.id) === k).length;
            return h('span', { key: k, style: { display: 'inline-flex', alignItems: 'center', gap: 5 } },
              h('span', { style: { width: 9, height: 9, borderRadius: 5, background: ST[k].color, display: 'inline-block' } }),
              h('span', { className: 'bm-muted' }, ST[k].label + ' ' + n));
          }));

        return h('div', { className: 'bm-root' },
          h('div', { className: 'bm-inner' },
            h('div', { className: 'bm-head' },
              h('h1', { className: 'bm-title' }, '建材订单系统 · 开发工作台'),
              h('span', { className: 'bm-sub' }, '开发时用的工具 · 文件都放在本机 · 不需要联网')),
            progressBar,
            statusRow,
            h('div', { className: 'bm-tabs' },
              TABS.map((t) => h('button', { key: t[0], className: 'bm-tab' + (tab === t[0] ? ' bm-active' : ''), onClick: () => this.setState({ tab: t[0] }) }, t[1]))),
            content),
          toast ? h('div', { className: 'bm-toast' }, toast) : null);
      }
    }

    // ────────────────────────────────────────────────────────────
    // 契约浏览器
    // ────────────────────────────────────────────────────────────
    class ContractExplorer extends React.Component {
      constructor(props) { super(props); this.state = { sel: '' }; }
      render() {
        const { plan } = this.props;
        const byId = {};
        (plan.modules || []).forEach((m) => { byId[m.id] = m; });
        const shared = plan.shared || {};
        const dtoFlow = plan.dtoFlow || {};

        const m = byId[this.state.sel];
        const filter = h('div', { style: { marginBottom: 10, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' } },
          h('span', { className: 'bm-muted' }, '看哪个模块：'),
          h('select', { className: 'bm-select', value: this.state.sel, onChange: (e) => this.setState({ sel: e.target.value }) },
            h('option', { value: '' }, '全部'),
            (plan.modules || []).map((x) => h('option', { key: x.id, value: x.id }, cnName(x.id)))));

        let modPanel = null;
        if (m) {
          const deps = (m.calls || []).map((d) => cnName(d)).join('、');
          const users = (plan.modules || []).filter((x) => (x.calls || []).includes(m.id)).map((x) => cnName(x.id)).join('、');
          modPanel = h('div', { className: 'bm-card' },
            h('div', { className: 'bm-card-h' }, h('b', null, cnName(m.id))),
            h('div', { className: 'bm-card-b' },
              h('div', { className: 'bm-field-label' }, '干什么'), h('div', null, m.duty),
              h('div', { className: 'bm-field-label' }, '先要谁做完'), h('div', null, deps || '不用等别人'),
              h('div', { className: 'bm-field-label' }, '哪些模块要用它'), h('div', null, users || '暂时没人用')));
        }

        const CENUM = { OrderType: '订单类型', DocType: '单据类型', SettleDirection: '结算方向', DiffType: '差异类型', OrderStatus: '订单状态' };
        const chips = (arr, map) => (arr || []).map((e, i) => h('span', { key: i, className: 'bm-chip' }, map ? (map[e] || e) : e));
        const sharedCard = h('div', { className: 'bm-card' },
          h('div', { className: 'bm-card-h' }, '共享约定（两边都要守）'),
          h('div', { className: 'bm-card-b' },
            h('div', { className: 'bm-field-label' }, '单据类型'), h('div', null, chips(shared.enums, CENUM)),
            h('div', { className: 'bm-field-label' }, '单号前缀'), h('div', null, chips(shared.serial)),
            h('div', { className: 'bm-field-label' }, '错误码'), h('div', null, chips(shared.errors)),
            h('div', { className: 'bm-field-label' }, '计量单位'), h('div', null, chips(shared.units))));

        const dtoRows = Object.keys(dtoFlow).map((d) => {
          const f = dtoFlow[d];
          const cons = (f.consumes || []).map((id) => cnName(id)).join('、');
          return h('div', { className: 'bm-row', key: d },
            h('span', { className: 'bm-grow' }, h('b', null, cnDto(d))),
            h('span', { className: 'bm-muted' }, '谁产出：' + cnName(f.produces) + '　谁用：' + (cons || '—')));
        });
        const dtoCard = h('div', { className: 'bm-card' },
          h('div', { className: 'bm-card-h' }, '数据怎么走'),
          h('div', { className: 'bm-card-b' }, dtoRows));

        return h('div', null, filter, modPanel, sharedCard, dtoCard);
      }
    }

    // ────────────────────────────────────────────────────────────
    // Agent 产出归档
    // ────────────────────────────────────────────────────────────
    class Archive extends React.Component {
      constructor(props) { super(props); this.state = { sel: '', summary: '' }; }
      archive() {
        const { plan, data, onSave, toast } = this.props;
        const m = (plan.modules || []).find((x) => x.id === this.state.sel);
        if (!m) return;
        const taskCard = buildTaskCard(plan, data, m);
        const acc = data.acceptance || {};
        const rec = { taskCard: taskCard, summary: this.state.summary, result: (acc[m.id] || []).length + '/' + (m.accept || []).length, contractVersion: data.contractVersion || plan.contractVersion, at: new Date().toISOString() };
        onSave({ archive: { ...(data.archive || {}), [m.id]: rec } });
        this.setState({ summary: '' });
        toast('已存档 ' + cnName(m.id));
      }
      render() {
        const { plan, data } = this.props;
        const archive = data.archive || {};
        const byId = {};
        (plan.modules || []).forEach((m) => { byId[m.id] = m; });
        const ids = Object.keys(archive);
        const bar = h('div', { style: { marginBottom: 10, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' } },
          h('span', { className: 'bm-muted' }, '给谁存档：'),
          h('select', { className: 'bm-select', value: this.state.sel, onChange: (e) => this.setState({ sel: e.target.value }) },
            h('option', { value: '' }, '—'),
            (plan.modules || []).map((m) => h('option', { key: m.id, value: m.id }, cnName(m.id)))),
          h('input', { className: 'bm-input', style: { maxWidth: 240 }, placeholder: '产出摘要（可选）', value: this.state.summary, onChange: (e) => this.setState({ summary: e.target.value }) }),
          h('button', { className: 'bm-btn bm-btn-p', onClick: () => this.archive() }, '存档'));
        const list = ids.length === 0
          ? h('p', { className: 'bm-muted' }, '暂无存档。谁干完活，就在这里存一份当时的说明、产出摘要和验收结果，方便回头查。')
          : ids.map((id) => {
            const rec = archive[id];
            const m = byId[id];
            return h('div', { className: 'bm-card', key: id },
              h('div', { className: 'bm-card-h' },
                h('span', { className: 'bm-grow' }, h('b', null, m ? cnName(id) : '未命名')),
                h('span', { className: 'bm-chip' }, '约定第 ' + (rec.contractVersion || '?') + ' 版'),
                h('span', { className: 'bm-chip' }, '验收 ' + (rec.result || '?'))),
              h('div', { className: 'bm-card-b' },
                rec.summary ? h('div', { className: 'bm-muted' }, rec.summary) : null,
                h('div', { className: 'bm-muted' }, '（当时给机器人的完整说明已存好，不在这里展开）')));
          });
        return h('div', null, bar, list);
      }
    }

    // ────────────────────────────────────────────────────────────
    // 契约版本 + 变更影响分析
    // ────────────────────────────────────────────────────────────
    class ContractVersion extends React.Component {
      constructor(props) { super(props); this.state = { dto: '', reason: '' }; }
      submit() {
        const { plan, data, onSave, toast } = this.props;
        const dto = this.state.dto;
        if (!dto) { toast('请先选一条共享约定'); return; }
        const flow = (plan.dtoFlow || {})[dto] || {};
        const affected = Array.from(new Set([flow.produces, ...(flow.consumes || [])].filter(Boolean)));
        const change = { id: Date.now(), dto, reason: this.state.reason, affected, status: 'pending', at: new Date().toISOString() };
        onSave({ contractChanges: [...(data.contractChanges || []), change] });
        this.setState({ dto: '', reason: '' });
        toast('已提交变更申请');
      }
      approve(id) {
        const { data, onSave, toast } = this.props;
        const changes = (data.contractChanges || []).map((c) => c.id === id ? { ...c, status: 'approved' } : c);
        const cur = (data.contractVersion || '1.0.0').split('.').map(Number);
        cur[2] = (cur[2] || 0) + 1;
        onSave({ contractChanges: changes, contractVersion: cur.join('.') });
        toast('已批准，约定版本升了一版');
      }
      render() {
        const { plan, data } = this.props;
        const dtoFlow = plan.dtoFlow || {};
        const changes = data.contractChanges || [];
        const byId = {};
        (plan.modules || []).forEach((m) => { byId[m.id] = m; });
        const dtoOpts = Object.keys(dtoFlow).map((d) => h('option', { key: d, value: d }, cnDto(d)));
        const flow = dtoFlow[this.state.dto] || {};
        const preview = this.state.dto ? Array.from(new Set([flow.produces, ...(flow.consumes || [])].filter(Boolean))) : [];
        const form = h('div', { className: 'bm-card' },
          h('div', { className: 'bm-card-h' }, '约定变更申请'),
          h('div', { className: 'bm-card-b' },
            h('div', { style: { display: 'flex', gap: 8, marginBottom: 8, flexWrap: 'wrap' } },
              h('select', { className: 'bm-select', value: this.state.dto, onChange: (e) => this.setState({ dto: e.target.value }) },
                h('option', { value: '' }, '选哪条约定'), dtoOpts),
              h('input', { className: 'bm-input', style: { maxWidth: 300 }, placeholder: '变更原因', value: this.state.reason, onChange: (e) => this.setState({ reason: e.target.value }) }),
              h('button', { className: 'bm-btn bm-btn-p', onClick: () => this.submit() }, '提交申请')),
            this.state.dto ? h('div', { className: 'bm-muted' }, '要通知的模块：' + (preview.length ? preview.map((id) => cnName(id)).join('、') : '无')) : null));
        const list = changes.length === 0 ? h('p', { className: 'bm-muted' }, '暂无变更。凡是改共享约定的，都要走：提申请 → 审批 → 升版本 → 通知相关模块。')
          : changes.map((c) => h('div', { className: 'bm-card', key: c.id },
            h('div', { className: 'bm-card-h' },
              h('span', { className: 'bm-code' }, c.dto),
              h('span', { className: 'bm-grow' }, c.reason || '（无说明）'),
              h('span', { className: 'bm-chip' + (c.status === 'approved' ? ' bm-green' : ' bm-amber') }, c.status === 'approved' ? '已批准' : '待审批'),
              c.status === 'pending' ? h('button', { className: 'bm-btn bm-btn-p', onClick: () => this.approve(c.id) }, '批准并升一版') : null),
            h('div', { className: 'bm-card-b' },
              h('div', { className: 'bm-muted' }, '受影响：' + ((c.affected || []).length ? c.affected.map((id) => id + (byId[id] ? '.' + byId[id].name : '')).join('、') : '无')))));
        return h('div', null,
          h('p', { className: 'bm-sub' }, '当前共享约定：第 ' + (data.contractVersion || plan.contractVersion) + ' 版'),
          form, list);
      }
    }

    // ────────────────────────────────────────────────────────────
    // 并行冲突预检
    // ────────────────────────────────────────────────────────────
    class ConflictPrecheck extends React.Component {
      constructor(props) { super(props); this.state = { sel: [] }; }
      toggle(id) {
        const sel = this.state.sel.includes(id) ? this.state.sel.filter((x) => x !== id) : this.state.sel.concat(id);
        this.setState({ sel });
      }
      render() {
        const { plan, data } = this.props;
        const byId = {};
        (plan.modules || []).forEach((m) => { byId[m.id] = m; });
        const sel = this.state.sel;
        const conflicts = [];
        for (let i = 0; i < sel.length; i++) for (let j = i + 1; j < sel.length; j++) {
          const a = byId[sel[i]], b = byId[sel[j]];
          if (!a || !b) continue;
          const overlap = (a.write || []).some((wa) => (b.write || []).some((wb) => wa.split('/')[0] === wb.split('/')[0]));
          if (overlap) conflicts.push({ kind: '会改到同一处', text: cnName(a.id) + ' 和 ' + cnName(b.id) + ' 会改到同一个地方' });
        }
        const frozen = new Set(data.frozenPhases || []);
        const done = data.acceptance || {};
        const depUsers = {};
        sel.forEach((id) => { const m = byId[id]; if (m) (m.calls || []).forEach((d) => { (depUsers[d] = depUsers[d] || []).push(id); }); });
        Object.keys(depUsers).forEach((dep) => {
          if (depUsers[dep].length > 1) {
            const m = byId[dep];
            const depFrozen = m && (frozen.has(m.phase) || (done[dep] || []).length === (m.accept || []).length);
            if (!depFrozen) conflicts.push({ kind: '都指望同一个模块', text: depUsers[dep].map((id) => cnName(id)).join('、') + ' 都指望 ' + cnName(dep) + '（还没定下来）' });
          }
        });
        const picker = h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 12 } },
          (plan.modules || []).map((m) => {
            const on = sel.includes(m.id);
            return h('button', { key: m.id, type: 'button', className: 'bm-chip', onClick: () => this.toggle(m.id),
              style: { cursor: 'pointer', background: on ? 'var(--dsw-alias-brand-primary, #2563eb)' : undefined, color: on ? '#fff' : undefined, borderColor: on ? 'var(--dsw-alias-brand-primary, #2563eb)' : undefined } },
              cnName(m.id));
          }));
        const result = sel.length < 2
          ? h('p', { className: 'bm-muted' }, '勾选 2 个以上准备并行下发的模块，检查写入范围交集与共享未冻结依赖。')
          : (conflicts.length === 0
            ? h('div', { className: 'bm-card' }, h('div', { className: 'bm-card-b bm-green' }, '✓ 未发现冲突，可并行下发。'))
            : h('div', { className: 'bm-card' }, h('div', { className: 'bm-card-h bm-red' }, '发现 ' + conflicts.length + ' 处冲突'),
              h('div', { className: 'bm-card-b' }, conflicts.map((c, i) => h('div', { key: i, className: 'bm-row' },
                h('span', { className: 'bm-chip bm-red' }, c.kind),
                h('span', { className: 'bm-grow' }, c.text))))));
        return h('div', null,
          h('p', { className: 'bm-muted' }, '先点几个准备同时开工的模块，看看它们会不会改到同一处、或者都在等同一个还没做完的模块。'),
          picker, result);
      }
    }

    // ────────────────────────────────────────────────────────────
    // 全局约束 Lint（#11）
    // ────────────────────────────────────────────────────────────
    class LintPanel extends React.Component {
      constructor(props) { super(props); this.state = { r: null, loading: false }; }
      async run() {
        this.setState({ loading: true });
        try { this.setState({ r: await api('/api/lint') }); }
        catch (e) { this.setState({ r: { ok: false, error: String(e.message || e) } }); }
        this.setState({ loading: false });
      }
      render() {
        const r = this.state.r;
        const body = [];
        body.push(h('div', { style: { marginBottom: 10, display: 'flex', gap: 8, alignItems: 'center' } },
          h('button', { className: 'bm-btn bm-btn-p', onClick: () => this.run(), disabled: this.state.loading }, this.state.loading ? '检查中…' : '检查代码'),
          r && r.ok ? h('span', { className: 'bm-muted' }, '已扫描 ' + r.filesScanned + ' 个文件') : null));
        if (r && r.ok && (!r.violations || r.violations.length === 0)) body.push(h('div', { className: 'bm-card' }, h('div', { className: 'bm-card-b bm-green' }, '✓ 未发现违规')));
        if (r && r.ok && r.violations && r.violations.length > 0) body.push(h('div', { className: 'bm-card' },
          h('div', { className: 'bm-card-h bm-red' }, '发现 ' + r.violations.length + ' 处违规'),
          h('div', { className: 'bm-card-b' }, r.violations.map((v, i) => h('div', { key: i, className: 'bm-row' },
            h('span', { className: 'bm-chip bm-red' }, '违规'),
            h('span', { className: 'bm-grow' }, soften(v.name)),
            h('span', { className: 'bm-muted bm-scope-tag' }, v.file))))));
        if (r && !r.ok) body.push(h('div', { className: 'bm-red' }, '扫描失败：' + (r.error || '未知')));
        return h('div', null,
          h('p', { className: 'bm-muted' }, '扫一遍代码，看有没有犯规矩：数据库语句写到了不该写的地方、手机端自己算钱、手机端出现打印相关字眼、绕过单号模块自己拼单号。'),
          body);
      }
    }

    // ────────────────────────────────────────────────────────────
    // docs 一键生成（#15）
    // ────────────────────────────────────────────────────────────
    class DocsPanel extends React.Component {
      constructor(props) { super(props); this.state = { r: null, loading: false }; }
      async gen(write) {
        this.setState({ loading: true });
        try { this.setState({ r: await api(write ? '/api/docs' : '/api/docs/preview', write ? { method: 'POST' } : undefined) }); }
        catch (e) { this.setState({ r: { ok: false, error: String(e.message || e) } }); }
        this.setState({ loading: false });
      }
      render() {
        const r = this.state.r;
        return h('div', null,
          h('p', { className: 'bm-muted' }, '根据模块清单，自动生成「路由说明」和「功能说明」两份文档，存到文档目录里。'),
          h('div', { style: { marginBottom: 10, display: 'flex', gap: 8 } },
            h('button', { className: 'bm-btn bm-btn-p', onClick: () => this.gen(true), disabled: this.state.loading }, '生成文档')),
          r && r.ok ? h('div', { className: 'bm-green', style: { marginBottom: 8 } }, '已生成：路由说明、功能说明（都存在文档目录里）') : null,
          r && !r.ok ? h('div', { className: 'bm-red' }, '生成失败：' + (r.error || '未知')) : null);
      }
    }

    // ────────────────────────────────────────────────────────────
    // GitHub 管理（#16）
    // ────────────────────────────────────────────────────────────
    class GithubPanel extends React.Component {
      constructor(props) { super(props); this.state = { repo: '', token: '', msg: '', tag: '', wf: '', ref: '', r: null, loading: false }; }
      componentDidMount() { this.sync(this.props); }
      componentDidUpdate(prev) { if (prev.data !== this.props.data) this.sync(this.props); }
      sync(props) { const g = props.data.github || {}; this.setState((s) => ({ repo: g.repo != null ? g.repo : s.repo, token: g.token != null ? g.token : s.token })); }
      saveCfg() {
        const { data, onSave, toast } = this.props;
        onSave({ github: { ...(data.github || {}), repo: this.state.repo, token: this.state.token } });
        toast('已保存仓库设置');
      }
      async call(path, body) {
        this.setState({ loading: true });
        try { this.setState({ r: await api(path, body ? { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) } : undefined) }); }
        catch (e) { this.setState({ r: { ok: false, error: String(e.message || e) } }); }
        this.setState({ loading: false });
      }
      render() {
        const r = this.state.r;
        const input = (key, ph, type) => h('input', { className: 'bm-input', style: { maxWidth: 200 }, type: type || 'text', placeholder: ph, value: this.state[key], onChange: (e) => this.setState({ [key]: e.target.value }) });
        return h('div', null,
          h('div', { className: 'bm-card' },
            h('div', { className: 'bm-card-h' }, '代码仓库设置'),
            h('div', { className: 'bm-card-b' },
              h('input', { className: 'bm-input', style: { marginBottom: 8 }, placeholder: '仓库地址', value: this.state.repo, onChange: (e) => this.setState({ repo: e.target.value }) }),
              h('input', { className: 'bm-input', type: 'password', placeholder: '访问令牌', value: this.state.token, onChange: (e) => this.setState({ token: e.target.value }) }),
              h('button', { className: 'bm-btn bm-btn-p', style: { marginTop: 6 }, onClick: () => this.saveCfg() }, '保存设置'))),
          h('div', { style: { display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10, alignItems: 'center' } },
            h('button', { className: 'bm-btn', onClick: () => this.call('/api/github/status'), disabled: this.state.loading }, '检查仓库工具'),
            input('msg', '提交说明'),
            h('button', { className: 'bm-btn', onClick: () => this.call('/api/github/commit', { message: this.state.msg }), disabled: this.state.loading }, '提交到仓库'),
            input('tag', '版本号'),
            h('button', { className: 'bm-btn', onClick: () => this.call('/api/github/release', { tag: this.state.tag }), disabled: this.state.loading }, '发布版本'),
            input('wf', '流程名'),
            input('ref', '分支'),
            h('button', { className: 'bm-btn', onClick: () => this.call('/api/github/workflow', { workflow: this.state.wf, ref: this.state.ref }), disabled: this.state.loading }, '运行自动流程')),
          r ? h('div', { className: 'bm-card' }, h('div', { className: 'bm-card-h' }, '结果'), h('div', { className: 'bm-card-b' }, h('pre', { className: 'bm-pre', style: { maxHeight: 300 } }, typeof r === 'string' ? r : JSON.stringify(r, null, 2)))) : null);
      }
    }

    class ErrorBoundary extends React.Component {
      constructor(props) { super(props); this.state = { error: null }; }
      static getDerivedStateFromError(error) { return { error }; }
      render() {
        if (this.state.error) {
          const msg = this.state.error && this.state.error.message ? this.state.error.message : String(this.state.error);
          return h('div', { className: 'bm-root' },
            h('div', { className: 'bm-inner' },
              h('div', { className: 'bm-red', style: { whiteSpace: 'pre-wrap' } }, '工作台渲染出错：\n' + msg)));
        }
        return this.props.children;
      }
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        const styleEl = document.createElement('style');
        styleEl.setAttribute('data-plugin', '@local/bm-workbench');
        styleEl.textContent = CSS;
        document.head.appendChild(styleEl);
        ctx.effect(() => () => { styleEl.remove(); });

        const uiWorkspace = ctx.get('uiWorkspace');
        const dispatchAgent = (text) => {
          try { copyText(text); } catch (e) { /* ignore */ }
          try {
            if (uiWorkspace && typeof uiWorkspace.startSession === 'function') uiWorkspace.startSession();
          } catch (e) { /* ignore */ }
        };

        ctx.slots.inject('sidebar.panellist', () => ctx.slots.register({
          name: 'sidebar.panellist', id: 'bm-workbench', order: 60, label: '建材系统工作台',
        }, PanelIcon));

        const Main = () => h(ErrorBoundary, null, h(Workbench, { onDispatchAgent: dispatchAgent }));

        ctx.slots.inject('main', () => ctx.slots.register({
          name: 'main', key: 'bm-workbench',
        }, Main));
      },
    };
  },
});
