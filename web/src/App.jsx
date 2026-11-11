import { useCallback, useEffect, useMemo, useState } from 'react'

const PREFIX = '/app/czxs'
const money = (v) => (v == null ? '—' : v)
const typeLabel = { delivery: '配送送货', delivery_return: '配送退货', pickup: '自提送货', pickup_return: '自提退货' }

async function api(path, opt = {}) {
  const token = localStorage.getItem('czxs_token')
  const res = await fetch(PREFIX + path, {
    ...opt,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(opt.headers || {}) },
  })
  const body = await res.json().catch(() => ({}))
  if (res.status === 401) {
    localStorage.removeItem('czxs_token')
    location.reload()
    throw new Error('登录已失效')
  }
  if (!res.ok || body.ok === false) throw new Error(body.message || '请求失败')
  return body.data
}

function Login({ onLogin }) {
  const [u, setU] = useState('admin')
  const [p, setP] = useState('')
  const [err, setErr] = useState('')
  return (
    <form className="login" onSubmit={async (e) => {
      e.preventDefault()
      try {
        const d = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ username: u, password: p }) })
        localStorage.setItem('czxs_token', d.token)
        localStorage.setItem('czxs_role', d.account.role || 'staff')
        localStorage.setItem('czxs_name', d.account.name || '')
        onLogin({ name: d.account.name, role: d.account.role })
      } catch (x) { setErr(x.message) }
    }}>
      <h2>瓷砖店销售系统</h2>
      <label>账号</label><input value={u} onChange={(e) => setU(e.target.value)} />
      <label>密码</label><input type="password" value={p} onChange={(e) => setP(e.target.value)} />
      {err && <div className="err">{err}</div>}
      <button style={{ width: '100%' }}>登 录</button>
    </form>
  )
}

function OrderDetail({ o }) {
  if (!o) return null
  return (
    <div style={{ marginTop: 12 }}>
      <h3>{o.order_no} · {typeLabel[o.type] || o.type}</h3>
      <p>客户：{o.customer_name} {o.customer_phone}　下单员：{o.account_name}　{o.created_at?.slice(0, 16).replace('T', ' ')}</p>
      <p>状态：{o.status === 'printed' ? '已打印' : '待打印'}　版本：v{o.version}　合计：<b>{o.order_total}</b>　累计：{o.cumulative_total}</p>
      <table>
        <thead><tr><th>品类</th><th>型号</th><th>批号</th><th>录入</th><th>结算</th><th>单价</th><th>小计</th></tr></thead>
        <tbody>{o.items.map((i, k) => (
          <tr key={k}><td>{i.group}</td><td>{i.name}</td><td>{i.batch_tag}</td>
            <td>{i.input_quantity}{i.input_unit}</td><td>{i.settlement_quantity}{i.settlement_unit}</td>
            <td>{i.unit_price}</td><td>{i.line_total}</td></tr>
        ))}</tbody>
      </table>
    </div>
  )
}

function OrderTable({ rows, onOpen }) {
  return (
    <table>
      <thead><tr><th>单号</th><th>类型</th><th>客户</th><th>金额</th><th>状态</th><th>操作</th></tr></thead>
      <tbody>{(rows || []).map((o) => (
        <tr key={o.id}>
          <td>{o.order_no}</td><td>{typeLabel[o.type] || o.type}</td><td>{o.customer_name}</td>
          <td>{o.order_total}</td><td>{o.status === 'printed' ? '已打印' : '待打印'}</td>
          <td><button className="secondary" onClick={() => onOpen(o.id)}>详情</button></td>
        </tr>
      ))}</tbody>
    </table>
  )
}

function Dashboard() {
  const [rows, setRows] = useState([])
  const [detail, setDetail] = useState(null)
  useEffect(() => { api('/api/orders/today').then(setRows).catch(() => {}) }, [])
  const open = async (id) => setDetail(await api('/api/orders/' + id))
  return (
    <>
      <h2>仪表盘 · 今日单据（{rows.length}）</h2>
      <table>
        <thead><tr><th>单号</th><th>类型</th><th>客户</th><th>金额</th><th>操作</th></tr></thead>
        <tbody>{rows.map((o) => (
          <tr key={o.id}><td>{o.order_no}</td><td>{typeLabel[o.type] || o.type}</td><td>{o.customer_name}</td>
            <td>{o.order_total}</td><td><button className="secondary" onClick={() => open(o.id)}>详情</button></td></tr>
        ))}</tbody>
      </table>
      <OrderDetail o={detail} />
    </>
  )
}

function Orders() {
  const [rows, setRows] = useState([])
  const [detail, setDetail] = useState(null)
  useEffect(() => { api('/api/orders/all').then(setRows).catch(() => {}) }, [])
  const open = async (id) => setDetail(await api('/api/orders/' + id))
  return (
    <>
      <h2>单据库（全部 {rows.length} 张）</h2>
      <OrderTable rows={rows} onOpen={open} />
      <OrderDetail o={detail} />
    </>
  )
}

function PrintPage() {
  const [rows, setRows] = useState([])
  useEffect(() => { api('/api/print/jobs').then(setRows).catch(() => {}) }, [])
  return (
    <>
      <h2>打印中枢</h2>
      <table><thead><tr><th>#</th><th>单号</th><th>类型</th><th>状态</th><th>时间</th></tr></thead>
        <tbody>{rows.map((j) => <tr key={j.id}><td>{j.id}</td><td>{j.doc_no}</td><td>{j.doc_type === 'note' ? '备注单' : '单据'}</td><td>{j.status === 'printed' ? '已打印' : '待打印'}</td><td>{j.created_at?.slice(0, 16).replace('T', ' ')}</td></tr>)}</tbody></table>
    </>
  )
}

const emptyCat = { group: '', name: '', batch: '', settlement_unit: '', conversion_unit: '', conversion: '1' }

function CategoryModal({ init, onClose, onDone }) {
  const [f, setF] = useState(init || emptyCat)
  const [err, setErr] = useState('')
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const save = async (e) => {
    e.preventDefault()
    try {
      const noConvert = f.conversion_unit.trim() === ''
      const body = JSON.stringify({ ...f, conversion: noConvert ? 1 : (Number(f.conversion) || 1) })
      if (init?.id) await api('/api/categories/' + init.id, { method: 'PUT', body })
      else await api('/api/categories', { method: 'POST', body })
      onDone()
    } catch (x) { setErr(x.message) }
  }
  return (
    <dialog open className="modal">
      <form onSubmit={save}>
        <h3>{init?.id ? '编辑型号' : '新增型号'}</h3>
        <label className="row">一级 品类：<input value={f.group} onChange={set('group')} required /></label>
        <label className="row">二级 型号：<input value={f.name} onChange={set('name')} required /></label>
        <label className="row">三级 批号：<input value={f.batch} onChange={set('batch')} required /></label>
        <label className="row">四级 计价单位：<input value={f.settlement_unit} onChange={set('settlement_unit')} required /></label>
        <label className="row">五级 换算单位（可选）：<input value={f.conversion_unit} onChange={set('conversion_unit')} /></label>
        {f.conversion_unit.trim() !== '' && (
          <div className="row">换算系数：1 <b>{f.conversion_unit}</b> = <input style={{ width: 70 }} value={f.conversion} onChange={set('conversion')} /> <b>{f.settlement_unit || '暂无'}</b></div>
        )}
        {err && <div className="err">{err}</div>}
        <button>保存</button> <button type="button" className="secondary" onClick={onClose}>取消</button>
      </form>
    </dialog>
  )
}

function Categories() {
  const [rows, setRows] = useState([])
  const [edit, setEdit] = useState(undefined)
  const load = useCallback(() => api('/api/categories').then(setRows).catch(() => {}), [])
  useEffect(() => { load() }, [load])
  const toggle = async (c) => { await api(`/api/categories/${c.id}/status?value=${c.status === 'active' ? 'disabled' : 'active'}`); load() }
  return (
    <>
      <h2>型号库</h2>
      <div style={{ marginBottom: 10 }}>
        <button onClick={() => setEdit(null)}>+ 新增型号</button>
        <button className="secondary" onClick={load}>刷新</button>
      </div>
      <table>
        <thead><tr><th>品类</th><th>型号</th><th>批号</th><th>计价单位</th><th>换算单位</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{rows.map((c) => (
          <tr key={c.id}>
            <td>{c.group}</td><td>{c.name}</td><td>{c.batch}</td><td>{c.settlement_unit}</td>
            <td>{c.conversion_unit ? `${c.conversion_unit}（1${c.conversion_unit}=${c.conversion}${c.settlement_unit}）` : '—'}</td>
            <td>{c.status === 'active' ? '启用' : '禁用'}</td>
            <td>
              <button className="secondary" onClick={() => toggle(c)}>{c.status === 'active' ? '禁用' : '启用'}</button>
              <button className="secondary" onClick={() => setEdit(c)}>编辑</button>
              <button className="danger" onClick={async () => { if (confirm('确认删除？')) { await api('/api/categories/' + c.id, { method: 'DELETE' }); load() } }}>删除</button>
            </td>
          </tr>
        ))}</tbody>
      </table>
      {edit !== undefined && <CategoryModal init={edit} onClose={() => setEdit(undefined)} onDone={() => { setEdit(undefined); load() }} />}
    </>
  )
}

function Prices() {
  const [cats, setCats] = useState([])
  const [customer, setCustomer] = useState('')
  const [form, setForm] = useState({ category_id: '', batch_tag: '', unit_price: '' })
  useEffect(() => { api('/api/categories').then(setCats).catch(() => {}) }, [])
  return (
    <>
      <h2>客户价格</h2>
      <p className="muted">价格按 客户+型号+批号 维护；首次下单也会自动写入。</p>
      <div style={{ margin: '12px 0' }}>
        <input placeholder="客户电话" value={customer} onChange={(e) => setCustomer(e.target.value)} />
        <select value={form.category_id} onChange={(e) => setForm({ ...form, category_id: e.target.value })}>
          <option value="">选择型号</option>
          {cats.map((c) => <option key={c.id} value={c.id}>{c.group} {c.name} {c.batch}</option>)}
        </select>
        <input placeholder="批号" value={form.batch_tag} onChange={(e) => setForm({ ...form, batch_tag: e.target.value })} />
        <input placeholder="单价" value={form.unit_price} onChange={(e) => setForm({ ...form, unit_price: e.target.value })} />
        <button onClick={async () => {
          try {
            const c = await api('/api/customers?phone=' + encodeURIComponent(customer))
            if (!c.exists) return alert('客户不存在')
            await api('/api/prices', { method: 'POST', body: JSON.stringify({ customer_id: c.id, category_id: Number(form.category_id), batch_tag: form.batch_tag, unit_price: form.unit_price }) })
            alert('已保存')
          } catch (e) { alert(e.message) }
        }}>保存价格</button>
      </div>
    </>
  )
}

const emptyAcc = { username: '', name: '', password: '', role: 'staff', status: 'active' }

function AccountModal({ init, onClose, onDone }) {
  const [f, setF] = useState(init ? { ...init, password: '' } : emptyAcc)
  const [err, setErr] = useState('')
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const save = async (e) => {
    e.preventDefault()
    try {
      if (init?.id) {
        await api('/api/accounts/' + init.id, { method: 'PUT', body: JSON.stringify({ name: f.name, role: f.role, status: f.status, password: f.password }) })
      } else {
        await api('/api/accounts', { method: 'POST', body: JSON.stringify(f) })
      }
      onDone()
    } catch (x) { setErr(x.message) }
  }
  return (
    <dialog open className="modal">
      <form onSubmit={save}>
        <h3>{init?.id ? '编辑账号' : '新增账号'}</h3>
        {!init?.id && <label className="row">账号名：<input value={f.username} onChange={set('username')} required /></label>}
        <label className="row">姓名：<input value={f.name} onChange={set('name')} required /></label>
        <label className="row">密码{init?.id ? '（留空不改）' : ''}：<input value={f.password} onChange={set('password')} required={!init?.id} /></label>
        <label className="row">权限：<select value={f.role} onChange={set('role')}><option value="admin">管理员</option><option value="staff">业务员</option></select></label>
        <label className="row">状态：<select value={f.status} onChange={set('status')}><option value="active">激活</option><option value="disabled">未激活</option></select></label>
        {err && <div className="err">{err}</div>}
        <button>保存</button> <button type="button" className="secondary" onClick={onClose}>取消</button>
      </form>
    </dialog>
  )
}

function Accounts({ isAdmin }) {
  const [rows, setRows] = useState([])
  const [edit, setEdit] = useState(undefined)
  const load = useCallback(() => api('/api/accounts').then(setRows).catch(() => {}), [])
  useEffect(() => { load() }, [load])
  const toggle = async (a) => { try { await api(`/api/accounts/${a.id}/status?value=${a.status === 'active' ? 'disabled' : 'active'}`); load() } catch (e) { alert(e.message) } }
  const del = async (a) => { if (!confirm(`删除账号 ${a.username}？`)) return; try { await api('/api/accounts/' + a.id, { method: 'DELETE' }); load() } catch (e) { alert(e.message) } }
  return (
    <>
      <h2>账号管理</h2>
      <div style={{ marginBottom: 10 }}>
        <button disabled={!isAdmin} title={isAdmin ? '' : '仅管理员可新增账号'} onClick={() => setEdit(null)}>+ 新增账号</button>
        <button className="secondary" onClick={load}>刷新</button>
      </div>
      <table>
        <thead><tr><th>账号</th><th>姓名</th><th>权限</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{rows.map((a) => (
          <tr key={a.id}>
            <td>{a.username}</td><td>{a.name}</td>
            <td>{a.role === 'admin' ? '管理员' : '业务员'}</td>
            <td>{a.status === 'active' ? '激活' : '未激活'}</td>
            <td>
              <button className="secondary" disabled={!isAdmin} onClick={() => toggle(a)}>{a.status === 'active' ? '禁用' : '启用'}</button>
              <button className="secondary" disabled={!isAdmin} onClick={() => setEdit(a)}>编辑</button>
              <button className="danger" disabled={!isAdmin} onClick={() => del(a)}>删除</button>
            </td>
          </tr>
        ))}</tbody>
      </table>
      {edit !== undefined && <AccountModal init={edit} onClose={() => setEdit(undefined)} onDone={() => { setEdit(undefined); load() }} />}
    </>
  )
}

function Settlement() {
  const [rows, setRows] = useState([])
  useEffect(() => { api('/api/settlement').then(setRows).catch(() => {}) }, [])
  return (
    <>
      <h2>结算汇总（按客户）</h2>
      <table><thead><tr><th>客户</th><th>电话</th><th>累计结算</th><th>最近交易</th></tr></thead>
        <tbody>{rows.map((r) => <tr key={r.id}><td>{r.name}</td><td>{r.phone}</td><td>{r.total}</td><td>{r.last?.slice(0, 10)}</td></tr>)}</tbody></table>
    </>
  )
}

function Settings() {
  const [s, setS] = useState({})
  const [msg, setMsg] = useState('')
  useEffect(() => { api('/api/settings').then(setS).catch(() => {}) }, [])
  return (
    <>
      <h2>系统配置</h2>
      <p>商家名称：<input value={s.merchant_name || ''} onChange={(e) => setS({ ...s, merchant_name: e.target.value })} /></p>
      <p>API 端口：<input value={s.api_port || '8080'} onChange={(e) => setS({ ...s, api_port: e.target.value })} /></p>
      <p>小票宽度：<select value={s.print_width || '80mm'} onChange={(e) => setS({ ...s, print_width: e.target.value })}><option>80mm</option><option>58mm</option></select></p>
      <button onClick={async () => { await api('/api/settings', { method: 'POST', body: JSON.stringify(s) }); setMsg('已保存') }}>保存</button>
      {msg && <span style={{ marginLeft: 8 }}>{msg}</span>}
      <p className="muted">端口修改需重启应用生效；客户端需同步更新地址。</p>
    </>
  )
}

const TABS = [['dashboard', '仪表盘'], ['orders', '单据'], ['print', '打印'], ['categories', '型号库'], ['prices', '价格'], ['accounts', '账号'], ['settlement', '结算'], ['settings', '配置']]

export default function App() {
  const [acc, setAcc] = useState(() => {
    const token = localStorage.getItem('czxs_token')
    return token ? { name: localStorage.getItem('czxs_name') || '用户', role: localStorage.getItem('czxs_role') || 'staff' } : null
  })
  const [tab, setTab] = useState('dashboard')
  const isAdmin = acc?.role === 'admin'
  const nav = useMemo(() => TABS, [])
  if (!acc) return <Login onLogin={setAcc} />
  return (
    <>
      <header>
        <strong>瓷砖店销售系统</strong>
        <nav>{nav.map(([k, l]) => <a key={k} href="#" onClick={(e) => { e.preventDefault(); setTab(k) }} style={{ fontWeight: tab === k ? 700 : 400 }}>{l}</a>)}</nav>
        <span className="who">{acc.name}（{isAdmin ? '管理员' : '业务员'}） <a href="#" style={{ color: '#fff' }} onClick={(e) => { e.preventDefault(); localStorage.removeItem('czxs_token'); setAcc(null) }}>退出</a></span>
      </header>
      <main>
        {tab === 'dashboard' && <Dashboard />}
        {tab === 'orders' && <Orders />}
        {tab === 'print' && <PrintPage />}
        {tab === 'categories' && <Categories />}
        {tab === 'prices' && <Prices />}
        {tab === 'accounts' && <Accounts isAdmin={isAdmin} />}
        {tab === 'settlement' && <Settlement />}
        {tab === 'settings' && <Settings />}
      </main>
    </>
  )
}
