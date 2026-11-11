import { useCallback, useEffect, useMemo, useState } from 'react'

const PREFIX = '/app/czxs'

async function api(path, options = {}) {
  const token = localStorage.getItem('czxs_token')
  const res = await fetch(PREFIX + path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(options.headers || {}),
    },
  })
  const body = await res.json().catch(() => ({}))
  if (!res.ok || body.ok === false) throw new Error(body.message || '请求失败')
  return body.data
}

function Login({ onLogin }) {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const submit = async (e) => {
    e.preventDefault()
    try {
      const data = await api('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      })
      localStorage.setItem('czxs_token', data.token)
      onLogin(data.account)
    } catch (err) {
      setError(err.message)
    }
  }
  return (
    <form className="login" onSubmit={submit}>
      <h2>瓷砖店销售系统</h2>
      <label>管理员账号</label>
      <input value={username} onChange={(e) => setUsername(e.target.value)} />
      <label>密码</label>
      <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
      {error && <div className="err">{error}</div>}
      <button style={{ width: '100%' }}>登 录</button>
      <p className="muted">网关地址：{PREFIX}/admin/</p>
    </form>
  )
}

const emptyForm = { group: '', name: '', batch: '', settlement_unit: '', conversion_unit: '', conversion: '1' }

function CategoryForm({ initial, onClose, onSaved }) {
  const [form, setForm] = useState(initial || emptyForm)
  const [error, setError] = useState('')
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })
  const ratioHint = (
    <span>
      换算系数：1 <b>{form.conversion_unit || '暂无'}</b> ={' '}
      <input style={{ width: 80 }} value={form.conversion} onChange={set('conversion')} />{' '}
      <b>{form.settlement_unit || '暂无'}</b>（例如 1 箱 = 10 片 就填 10）
    </span>
  )
  const save = async (e) => {
    e.preventDefault()
    setError('')
    try {
      const payload = {
        group: form.group,
        name: form.name,
        batch: form.batch,
        settlement_unit: form.settlement_unit,
        conversion_unit: form.conversion_unit,
        conversion: Number(form.conversion) || 1,
      }
      if (initial?.id) {
        await api('/api/categories/' + initial.id, { method: 'PUT', body: JSON.stringify(payload) })
      } else {
        await api('/api/categories', { method: 'POST', body: JSON.stringify(payload) })
      }
      onSaved()
    } catch (err) {
      setError(err.message)
    }
  }
  return (
    <form onSubmit={save}>
      <h3>{initial?.id ? '编辑型号' : '新增型号'}</h3>
      <label className="row">一级 品类：<input value={form.group} onChange={set('group')} placeholder="墙砖 / 地砖" required /></label>
      <label className="row">二级 型号：<input value={form.name} onChange={set('name')} placeholder="T6 / p3 / c200" required /></label>
      <label className="row">三级 批号：<input value={form.batch} onChange={set('batch')} placeholder="789 / ii8" required /></label>
      <label className="row">四级 计价单位：<input value={form.settlement_unit} onChange={set('settlement_unit')} placeholder="片 / 箱" required /></label>
      <label className="row">五级 换算单位：<input value={form.conversion_unit} onChange={set('conversion_unit')} placeholder="箱 / 件" required /></label>
      <div className="row">{ratioHint}</div>
      {error && <div className="err">{error}</div>}
      <div style={{ marginTop: 12 }}>
        <button type="submit">保存</button>
        <button type="button" className="secondary" onClick={onClose}>取消</button>
      </div>
    </form>
  )
}

function Categories({ onLogout }) {
  const [list, setList] = useState([])
  const [editing, setEditing] = useState(null)
  const [showForm, setShowForm] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setList(await api('/api/categories'))
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const act = async (path, method = 'POST') => {
    try {
      await api(path, { method })
      await load()
    } catch (err) {
      alert(err.message)
    }
  }

  return (
    <>
      <h2>型号库</h2>
      <div style={{ marginBottom: 10 }}>
        <button onClick={() => { setEditing(null); setShowForm(true) }}>+ 新增型号</button>
        <button className="secondary" onClick={() => act('/api/categories', 'GET')}>刷新</button>
      </div>
      {error && <div className="err">{error}</div>}
      <table>
        <thead>
          <tr>
            <th>品类</th><th>型号</th><th>批号</th><th>计价单位</th><th>换算单位</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          {list.map((c) => (
            <tr key={c.id}>
              <td>{c.group}</td>
              <td>{c.name}</td>
              <td>{c.batch}</td>
              <td>{c.settlement_unit}</td>
              <td>{c.conversion_unit}（1{c.conversion_unit}={c.conversion}{c.settlement_unit}）</td>
              <td>{c.status === 'active' ? '启用' : '禁用'}</td>
              <td>
                <button className="secondary" onClick={() => act(`/api/categories/${c.id}/status?value=${c.status === 'active' ? 'disabled' : 'active'}`)}>
                  {c.status === 'active' ? '禁用' : '启用'}
                </button>
                <button className="secondary" onClick={() => { setEditing(c); setShowForm(true) }}>编辑</button>
                <button className="danger" onClick={() => { if (confirm('确认删除该型号？')) act(`/api/categories/${c.id}`, 'DELETE') }}>删除</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {showForm && (
        <dialog open style={{ position: 'fixed', top: '10%', left: '50%', transform: 'translateX(-50%)', background: '#fff', zIndex: 10 }}>
          <CategoryForm
            initial={editing ? { ...editing, conversion: String(editing.conversion) } : null}
            onClose={() => setShowForm(false)}
            onSaved={() => { setShowForm(false); load() }}
          />
        </dialog>
      )}
    </>
  )
}

function GatewayInfo() {
  const [info, setInfo] = useState(null)
  useEffect(() => { api('/api/gateway/whoami').then(setInfo).catch(() => {}) }, [])
  return (
    <>
      <h2>网关身份</h2>
      <p className="muted">这些值由飞牛统一网关透传（X-Trim-* 头），应用不信任客户端自报身份。</p>
      <pre style={{ background: '#fff', border: '1px solid #e3e6ea', padding: 12 }}>
        {JSON.stringify(info, null, 2)}
      </pre>
    </>
  )
}

export default function App() {
  const [account, setAccount] = useState(() => (localStorage.getItem('czxs_token') ? { name: '管理员' } : null))
  const [tab, setTab] = useState('categories')
  const nav = useMemo(() => [
    ['categories', '型号库'],
    ['gateway', '网关身份'],
  ], [])

  const logout = () => {
    localStorage.removeItem('czxs_token')
    setAccount(null)
  }

  if (!account) return <Login onLogin={setAccount} />

  return (
    <>
      <header>
        <strong>瓷砖店销售系统</strong>
        <nav>
          {nav.map(([k, label]) => (
            <a key={k} href="#" onClick={(e) => { e.preventDefault(); setTab(k) }} style={{ fontWeight: tab === k ? 700 : 400 }}>
              {label}
            </a>
          ))}
        </nav>
        <span className="who">
          {account.name}{' '}
          <a href="#" onClick={(e) => { e.preventDefault(); logout() }} style={{ color: '#fff' }}>退出</a>
        </span>
      </header>
      <main>
        {tab === 'categories' && <Categories onLogout={logout} />}
        {tab === 'gateway' && <GatewayInfo />}
      </main>
    </>
  )
}
