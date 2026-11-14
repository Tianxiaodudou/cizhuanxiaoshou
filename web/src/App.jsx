import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

function Modal({ children, width = 720, onClose }) {
  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.45)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div onClick={(e) => e.stopPropagation()} style={{ position: 'relative', background: '#fff', borderRadius: 10, padding: '18px 22px', maxHeight: '88vh', overflow: 'auto', width, boxShadow: '0 12px 40px rgba(0,0,0,.25)' }}>
        <button onClick={onClose} title="关闭" style={{ position: 'absolute', top: 8, right: 10, background: 'transparent', color: '#666', fontSize: 20, lineHeight: '20px', padding: '4px 8px' }}>×</button>
        {children}
      </div>
    </div>
  )
}

const PREFIX = '/app/czxs'
const money = (v) => (v == null ? '—' : v)
const isReturnType = (t) => t.endsWith('_return')
const isPhone = (v) => {
  const s = String(v).replace(/[\s-]/g, '')
  return /^1[3-9]\d{9}$/.test(s) || /^0\d{2,3}\d{7,8}(\d{1,6})?$/.test(s) || /^\d{5,20}$/.test(s)
}
const phoneInput = (v) => String(v).replace(/[^0-9-]/g, '').slice(0, 20)

const amountLabel = (t) => (t === 'receipt' ? '已收' : t === 'refund' ? '已退' : (t.endsWith('_return') ? '应退' : '应收'))
const typeLabel = { delivery: '配送', delivery_return: '配退', pickup: '自送', pickup_return: '自退', receipt: '收款', refund: '退款', note: '备注' }

async function api(path, opt = {}) {
  const token = localStorage.getItem('czxs_token')
  const res = await fetch(PREFIX + path, {
    ...opt,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(opt.headers || {}) },
  })
  const text = await res.text()
  let body = {}
  try { body = text ? JSON.parse(text) : {} } catch (e) {
    throw new Error('网关返回的不是 JSON（可能登录失效或被重定向），请刷新页面重试')
  }
  if (res.status === 401) {
    localStorage.removeItem('czxs_token')
    location.reload()
    throw new Error('登录已失效')
  }
  if (!res.ok || body.ok === false) throw new Error(body.message || '请求失败')
  return body.data
}

function Login({ onLogin }) {
  const [u, setU] = useState('')
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


function OrderDetailModal({ id, startEdit, onClose, onChanged }) {
  const [o, setO] = useState(null)
  const [editing, setEditing] = useState(!!startEdit)
  const [remark, setRemark] = useState('')
  const [lines, setLines] = useState([])
  const [err, setErr] = useState('')
  const load = useCallback(async () => {
    const d = await api('/api/orders/' + id)
    setO(d); setRemark(d.remark || ''); setLines(d.items.map((i) => ({ ...i, input_quantity: i.input_quantity, unit_price: i.unit_price })))
  }, [id])
  useEffect(() => { load().catch((e) => setErr(e.message)) }, [load])
  if (!o) return (<Modal width={720} onClose={onClose}><p>{err || '加载中…'}</p><button onClick={onClose}>关闭</button></Modal>)
  const archived = o.status === 'archived'
  const hasItems = ['delivery', 'delivery_return', 'pickup', 'pickup_return'].includes(o.type)
  const isAdmin = (localStorage.getItem('czxs_role') || 'staff') === 'admin'
  const blindPrice = !isAdmin && o.customer_price_mode === 'hidden'
  const save = async () => {
    if (!confirm('确认保存修改？')) return
    try {
      await api('/api/orders/' + id, { method: 'PUT', body: JSON.stringify({ version: o.version, remark, items: lines.map((l) => ({ category_id: l.category_id, batch_tag: l.batch_tag, input_unit: l.input_unit, input_quantity: l.input_quantity, unit_price: l.unit_price })) }) })
      setEditing(false); await load(); onChanged?.()
    } catch (e) { setErr(e.message) }
  }
  const del = async () => {
    if (!confirm('确认删除该单据？')) return
    try { await api('/api/orders/' + id, { method: 'DELETE' }); onChanged?.(); onClose() } catch (e) { alert(e.message) }
  }
  const archive = async () => {
    if (!confirm('归档后不可修改、不可删除，只能作为开新单的基础，确认归档？')) return
    try { await api('/api/orders/' + id + '/archive', { method: 'POST' }); await load(); onChanged?.() } catch (e) { alert(e.message) }
  }
  return (
    <Modal width={820} onClose={onClose}>
      <h3>{o.order_no} · {typeLabel[o.type] || o.type} {archived && <span style={{ color: '#c62828' }}>（已归档）</span>}</h3>
      <p>客户：{o.customer_phone} / {o.customer_name} / {o.customer_address || '—'}　下单员：{o.account_name}</p>
      <p>备注：{o.remark || '（空）'}</p>
      <p>时间：{o.created_at?.slice(0, 16).replace('T', ' ')}　状态：{archived ? '已归档' : o.status === 'printed' ? '已打印' : '待打印'}　版本：v{o.version}　{amountLabel(o.type)}：<b>{Math.abs(Number(o.order_total)).toFixed(2)}</b></p>
      {!hasItems && (o.type === 'receipt' || o.type === 'refund') && (
        <table>
          <thead><tr><th>付款方式</th><th>金额</th></tr></thead>
          <tbody>{(o.payments || []).map((p, k) => (
            <tr key={k}><td>{p.method}</td><td>{p.amount}</td></tr>
          ))}</tbody>
        </table>
      )}
      {hasItems && (
      <table>
        <thead><tr><th>序号</th><th>品类</th><th>型号</th><th>批号</th><th>录入</th><th>结算</th><th>单价</th><th>小计</th>{editing && <th>排序</th>}</tr></thead>
        <tbody>{lines.map((i, k) => (
          <tr key={i.id || k}><td>{k + 1}</td><td>{i.group}</td><td>{i.name}</td><td>{i.batch_tag}</td>
            <td>{editing ? (<><input style={{ width: 80 }} value={lines[k].input_quantity} onChange={(e) => setLines((ls) => ls.map((x, j) => j === k ? { ...x, input_quantity: e.target.value } : x))} /> <span>{i.input_unit}</span></>) : `${i.input_quantity}${i.input_unit}`}</td>
            <td>{i.settlement_quantity}{i.settlement_unit}</td>
            <td>{blindPrice ? '***' : (editing ? <input style={{ width: 80 }} value={lines[k].unit_price} onChange={(e) => setLines((ls) => ls.map((x, j) => j === k ? { ...x, unit_price: e.target.value } : x))} /> : i.unit_price)}</td>
            <td>{blindPrice ? '***' : i.line_total}</td>
            {editing && <td>
              <button type="button" className="secondary" onClick={() => setLines((ls) => { const a = [...ls]; if (k > 0) { [a[k - 1], a[k]] = [a[k], a[k - 1]] } return a })}>↑</button>
              <button type="button" className="secondary" onClick={() => setLines((ls) => { const a = [...ls]; if (k < a.length - 1) { [a[k + 1], a[k]] = [a[k], a[k + 1]] } return a })}>↓</button>
              <button type="button" className="danger" onClick={() => setLines((ls) => ls.filter((_, j) => j !== k))}>删</button>
            </td>}</tr>
        ))}</tbody>
      </table>
      )}
      {editing && <div className="row">备注：<input value={remark} onChange={(e) => setRemark(e.target.value)} /></div>}
      {err && <div className="err">{err}</div>}
      <div style={{ marginTop: 10 }}>
        {hasItems && !editing && !archived && <button onClick={() => setEditing(true)}>编辑</button>}
        {editing && <button onClick={save}>保存修改</button>}
        {!archived && <button className="secondary" onClick={archive}>归档</button>}
        {!archived && <button className="danger" onClick={del}>删除</button>}
        <button className="secondary" onClick={onClose}>关闭</button>
      </div>
      {archived && (
        <p className="muted">已归档：不可修改、不可删除；请到「仪表盘」以该客户信息开新单（送货/退货/备注），或到「结算」查看该客户汇总。</p>
      )}
    </Modal>
  )
}
function OrderTable({ rows, onDetail, onDelete, onArchive, canEdit = true }) {
  return (
    <table>
      <thead><tr><th>单号</th><th>类型</th><th>客户（电话/姓名/地址）</th><th>金额</th><th>状态</th><th>操作</th></tr></thead>
      <tbody>{(rows || []).map((o) => (
        <tr key={o.id}>
          <td>{o.order_no}</td><td>{typeLabel[o.type] || o.type}</td>
          <td>{o.customer_phone}<br />{o.customer_name}<br />{o.customer_address || ''}</td>
          <td>{amountLabel(o.type)} {Math.abs(Number(o.order_total)).toFixed(2)}</td><td>{o.status === 'archived' ? '已归档' : o.status === 'printed' ? '已打印' : '待打印'}</td>
          <td>
            <button className="secondary" onClick={() => onDetail(o.id)}>详情</button>
            <button className="secondary" disabled={!canEdit || o.status === 'archived'} onClick={() => onDetail(o.id, true)}>编辑</button>
            <button className="secondary" disabled={o.status === 'archived'} onClick={() => onArchive(o.id)}>归档</button>
            <button className="danger" disabled={!canEdit || o.status === 'archived'} onClick={() => onDelete(o.id)}>删除</button>
          </td>
        </tr>
      ))}</tbody>
    </table>
  )
}


function Wheel({ options, value, onChange, width = 120 }) {
  const ref = useRef(null)
  const idx = Math.max(0, options.indexOf(value))
  useEffect(() => {
    if (ref.current) ref.current.scrollTop = idx * 32
  }, [idx, options.length])
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const onWheel = (e) => {
      e.preventDefault()
      const dir = e.deltaY > 0 ? 1 : -1
      const next = Math.max(0, Math.min(options.length - 1, idx + dir))
      el.scrollTo({ top: next * 32, behavior: 'smooth' })
      if (options[next] !== undefined && options[next] !== value) onChange(options[next])
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [idx, options, value])
  return (
    <div style={{ position: 'relative', width }}>
      <div style={{ position: 'absolute', top: 64, height: 32, left: 0, right: 0, background: 'rgba(21,101,192,.10)', borderTop: '1px solid #1565c0', borderBottom: '1px solid #1565c0', pointerEvents: 'none', zIndex: 2 }} />
      <div ref={ref} onScroll={(e) => {
        const i = Math.round(e.target.scrollTop / 32)
        const v = options[i]
        if (v && v !== value) onChange(v)
      }} style={{ height: 160, overflowY: 'scroll', scrollSnapType: 'y mandatory', border: '1px solid #cfd6df', borderRadius: 6, background: '#fff', scrollbarWidth: 'none' }}>
        <div style={{ height: 64 }} />
        {options.map((o, i) => (
          <div key={o} style={{ height: 32, lineHeight: '32px', textAlign: 'center', scrollSnapAlign: 'center', fontWeight: i === idx ? 700 : 400, color: i === idx ? '#1565c0' : '#666' }}>{o}</div>
        ))}
        <div style={{ height: 64 }} />
      </div>
    </div>
  )
}

function ProductPicker({ cats, onClose, onPick }) {
  const groups = [...new Set(cats.map((c) => c.group))]
  const [group, setGroup] = useState(groups[0] || '')
  const names = [...new Set(cats.filter((c) => c.group === group).map((c) => c.name))]
  const [name, setName] = useState(names[0] || '')
  const batches = cats.filter((c) => c.group === group && c.name === name).map((c) => c.batch || '(无批号)')
  const [batch, setBatch] = useState(batches[0] || '(无批号)')
  useEffect(() => { const n = [...new Set(cats.filter((c) => c.group === group).map((c) => c.name))]; setName(n[0] || ''); setBatch('') }, [group])
  useEffect(() => { const b = cats.filter((c) => c.group === group && c.name === name).map((c) => c.batch || '(无批号)'); setBatch(b[0] || '(无批号)') }, [name])
  const realBatch = batch === '(无批号)' ? '' : batch
  const current = cats.find((c) => c.group === group && c.name === name && (c.batch || '') === realBatch)
  return (
    <Modal width={480} onClose={onClose}>
      <h3>选择商品（品类 → 型号 → 批号）</h3>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'center' }}>
        <div><div className="muted">品类</div><Wheel options={groups} value={group} onChange={setGroup} /></div>
        <div><div className="muted">型号</div><Wheel options={names} value={name} onChange={setName} /></div>
        <div><div className="muted">批号</div><Wheel options={batches} value={batch} onChange={setBatch} /></div>
      </div>
      <p className="muted" style={{ textAlign: 'center' }}>{group} / {name} / {(batch === '(无批号)' ? '无批号' : batch)}　计价单位：{current?.settlement_unit || '—'}　默认价：{current?.default_price || '—'}</p>
      <div style={{ textAlign: 'center' }}>
        <button disabled={!current} onClick={() => { onPick(current); onClose() }}>确定</button>{' '}
        <button type="button" className="secondary" onClick={onClose}>取消</button>
      </div>
    </Modal>
  )
}

function newLine() {
  return { category_id: '', batch_tag: '', input_unit: '', input_quantity: '', unit_price: '', locked: false, units: [] }
}

function OrderModal({ type, onClose, onDone }) {
  const [cats, setCats] = useState([])
  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [addr, setAddr] = useState('')
  const [looked, setLooked] = useState(false)
  const [isNew, setIsNew] = useState(false)
  const [lines, setLines] = useState([newLine()])
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [picker, setPicker] = useState(null)
  const [returns, setReturns] = useState([])
  const [matches, setMatches] = useState([])
  const isReturn = isReturnType(type)
  const isAdmin = (localStorage.getItem('czxs_role') || 'staff') === 'admin'
  const [priceMode, setPriceMode] = useState('open')
  const [remark, setRemark] = useState('')
  useEffect(() => { api('/api/categories').then(setCats).catch(() => {}) }, [])
  const setLine = (i, patch) => setLines((ls) => ls.map((l, k) => (k === i ? { ...l, ...patch } : l)))
  const applyCustomer = async (c) => {
    setLooked(true); setIsNew(false)
    setPhone(c.phone); setName(c.name); setAddr(c.addr || '')
    setPriceMode(c.price_mode || 'open'); setMatches([])
    if (isReturn) {
      try { setReturns(await api('/api/returns/list?phone=' + encodeURIComponent(c.phone))) } catch (x) { setErr(x.message) }
    }
  }
  const lookup = async () => {
    setBusy(true); setErr(''); setMatches([])
    try {
      const q = phone.trim()
      if (q.length < 1) throw new Error('请输入电话、姓名或地址关键字')
      const list = await api('/api/customers/search?q=' + encodeURIComponent(q))
      if (list.length === 1) {
        await applyCustomer(list[0])
      } else if (list.length > 1) {
        setMatches(list)
        setLooked(true); setIsNew(false)
      } else {
        setLooked(true); setIsNew(true); setName(''); setAddr(''); setPriceMode('open'); setReturns([])
        if (!isPhone(q)) throw new Error('新客户电话格式不正确（手机号或座机号）')
      }
    } catch (x) { setErr(x.message || '查询失败') } finally { setBusy(false) }
  }

  const loadPrice = async (i, catId, batch, fallback) => {
    if (!phone || !catId) return
    try {
      const d = await api('/api/prices?phone=' + encodeURIComponent(phone) + '&category_id=' + catId + '&batch_tag=' + encodeURIComponent(batch || ''))
      setLine(i, { unit_price: d.unit_price, locked: true })
    } catch (e) {
      setLine(i, { unit_price: fallback || '', locked: false })
    }
  }
  const applyCat = (i, c) => {
    const avail = isReturn ? (returns.find((r) => r.category_id === c.id && (r.batch_tag || '') === (c.batch || ''))?.available || '0') : null
    const blind = priceMode === 'hidden' && !isAdmin
    setLine(i, { category_id: c.id, batch_tag: c.batch, input_unit: c.settlement_unit, units: [c.settlement_unit, c.conversion_unit].filter(Boolean), unit_price: '', locked: blind, available: avail, focused: false, conv: Number(c.conversion) || 1, convUnit: c.conversion_unit || '' })
    if (!blind) loadPrice(i, c.id, c.batch, c.default_price)
  }
  const submit = async (e) => {
    e.preventDefault(); setErr('')
    try {
      if (!looked) throw new Error('请先点击“查客户”')
      if (type === 'refund' && refundable != null && total > Number(refundable) + 0.001) {
        throw new Error('退款金额超过可退金额（可退 ' + refundable + '）')
      }
      if (!name) throw new Error('请填写客户姓名')
      if (isNew && !isPhone(phone)) throw new Error('客户电话格式不正确（手机号 11 位，或座机含区号如 010-12345678）')
      const items = lines.filter((l) => l.category_id && l.input_quantity).map((l) => ({
        category_id: Number(l.category_id), batch_tag: l.batch_tag, input_unit: l.input_unit,
        input_quantity: l.input_quantity, unit_price: l.locked ? undefined : l.unit_price,
      }))
      if (!items.length) throw new Error('请至少录入一行明细')
      await api('/api/orders', { method: 'POST', body: JSON.stringify({ type, phone, customer_name: name, address: addr, price_mode: priceMode, remark, items }) })
      onDone()
    } catch (x) { setErr(x.message) }
  }
  const returnCats = isReturn
    ? cats.filter((c) => returns.some((r) => r.category_id === c.id && (r.batch_tag || '') === (c.batch || '')))
    : cats
  return (
    <Modal width={780} onClose={onClose}>
      <form onSubmit={submit}>
        <h3>{typeLabel[type]}单</h3>
        <div className="row">客户电话：<input value={phone} maxLength={30} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); lookup() } }} onChange={(e) => { setPhone(e.target.value); setMatches([]) }} placeholder="电话 / 姓名 / 地址关键字" /> <button type="button" className="secondary" disabled={busy || !phone} onClick={lookup}>{busy ? '查询中…' : '查客户'}</button></div>
        {err && <div className="err">{err}</div>}
        {matches.length > 0 && (
          <div className="row" style={{ flexWrap: 'wrap' }}>
            匹配到 {matches.length} 个客户：
            {matches.map((c) => <button key={c.id} type="button" className="secondary" onClick={() => applyCustomer(c)}>{c.name} / {c.phone} / {c.addr || '无地址'}</button>)}
          </div>
        )}
        {looked && isNew && (
          <div className="row">客户电话（新客户，仅数字）：<input value={phone} maxLength={20} onChange={(e) => setPhone(phoneInput(e.target.value))} /></div>
        )}
        {looked && (
          <>
            <div className="row">客户姓名：<input value={name} onChange={(e) => setName(e.target.value)} /></div>
            <div className="row">客户地址：<input value={addr} onChange={(e) => setAddr(e.target.value)} /></div>
            <div className="row">价格模式：
              <select value={priceMode} onChange={(e) => setPriceMode(e.target.value)} disabled={!isAdmin}>
                <option value="open">明价（正常开单，单价必填）</option>
                <option value="hidden">暗价（单价仅管理员可见）</option>
              </select>
            </div>
          </>
        )}
        <table>
          <thead><tr><th>序号</th><th>商品</th><th>批号</th><th>单位</th><th>数量</th><th>单价</th><th>操作</th></tr></thead>
          <tbody>{lines.map((l, i) => (
            <tr key={i}>
              <td>{i + 1}</td>
              <td><button type="button" className="secondary" onClick={() => setPicker(i)}>
                {l.category_id ? (() => { const c = cats.find((x) => x.id === l.category_id); return c ? `${c.group} ${c.name} ${c.batch}` : '已选' })() : '选择商品'}
              </button></td>
              <td><input value={l.batch_tag} onChange={(e) => { setLine(i, { batch_tag: e.target.value }); loadPrice(i, l.category_id, e.target.value, '') }} /></td>
              <td><select value={l.input_unit} onChange={(e) => setLine(i, { input_unit: e.target.value })}>
                {(l.units.length ? l.units : ['']).map((u) => <option key={u} value={u}>{u}</option>)}
              </select></td>
              <td><input style={{ width: 90 }} value={l.input_quantity}
                placeholder={!l.focused && !l.input_quantity && isReturn && l.available ? `可退${(l.convUnit && l.input_unit === l.convUnit ? Number(l.available) / (l.conv || 1) : Number(l.available)).toFixed(2).replace(/\.00$/, '')}` : ''}
                onFocus={() => setLine(i, { focused: true })} onBlur={() => setLine(i, { focused: false })}
                onChange={(e) => setLine(i, { input_quantity: e.target.value })} /></td>
              <td>{isReturn ? <span>{l.unit_price || '—'}</span> : <input style={{ width: 80 }} value={priceMode === 'hidden' && !isAdmin ? '' : l.unit_price}
                disabled={priceMode === 'hidden' && !isAdmin}
                placeholder={priceMode === 'hidden' && !isAdmin ? '自动取恒定价' : ''}
                readOnly={l.locked} onChange={(e) => setLine(i, { unit_price: e.target.value })} />}</td>
              <td>
                <button type="button" className="secondary" onClick={() => setLines((ls) => { const a = [...ls]; if (i > 0) { [a[i - 1], a[i]] = [a[i], a[i - 1]] } return a })}>↑</button>
                <button type="button" className="secondary" onClick={() => setLines((ls) => { const a = [...ls]; if (i < a.length - 1) { [a[i + 1], a[i]] = [a[i], a[i + 1]] } return a })}>↓</button>
                <button type="button" className="secondary" onClick={() => setLines((ls) => ls.filter((_, k) => k !== i))}>删</button>
              </td>
            </tr>
          ))}</tbody>
        </table>
        <button type="button" className="secondary" onClick={() => setLines((ls) => [...ls, newLine()])}>+ 添加一行</button>
        <div className="row">备注：<input value={remark} onChange={(e) => setRemark(e.target.value)} style={{ width: '100%' }} /></div>
        <div style={{ marginTop: 10 }}><button>提交打印</button> <button type="button" className="secondary" onClick={onClose}>取消</button></div>
      </form>
      {picker !== null && <ProductPicker cats={returnCats} onClose={() => setPicker(null)} onPick={(c) => applyCat(picker, c)} />}
    </Modal>
  )
}

function PaymentModal({ type, onClose, onDone }) {
  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [addr, setAddr] = useState('')
  const [looked, setLooked] = useState(false)
  const [lines, setLines] = useState([{ method: '微信', custom: '', amount: '' }])
  const setLine = (i, patch) => setLines((ls) => ls.map((l, k) => (k === i ? { ...l, ...patch } : l)))
  const total = lines.reduce((s2, l) => s2 + (Number(l.amount) || 0), 0)
  const [remark, setRemark] = useState('')
  const [refundable, setRefundable] = useState(null)
  const [discount, setDiscount] = useState('')
  const [discountType, setDiscountType] = useState('')
  const [outstanding, setOutstanding] = useState(null)
  const [matches, setMatches] = useState([])
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const presets = usePresets()

  const applyCustomer = async (c) => {
    setLooked(true); setPhone(c.phone); setName(c.name); setAddr(c.addr || ''); setMatches([])
    if (type === 'refund') {
      try { const r = await api('/api/refundable?phone=' + encodeURIComponent(c.phone)); setRefundable(r.refundable) } catch (x) { setErr(x.message) }
    }
  }
  const lookup = async () => {
    setBusy(true); setErr(''); setMatches([])
    try {
      const q = phone.trim()
      if (q.length < 1) throw new Error('请输入电话、姓名或地址关键字')
      const list = await api('/api/customers/search?q=' + encodeURIComponent(q))
      if (list.length === 1) {
        await applyCustomer(list[0])
      } else if (list.length > 1) {
        setMatches(list); setLooked(true)
      } else {
        setLooked(true); setName(''); setAddr('')
        if (!isPhone(q)) throw new Error('新客户电话格式不正确（手机号或座机号）')
      }
    } catch (x) { setErr(x.message || '查询失败') } finally { setBusy(false) }
  }

  const submit = async (e) => {
    e.preventDefault()
    try {
      if (!looked) throw new Error('请先点击“查客户”')
      await api('/api/payments', { method: 'POST', body: JSON.stringify({ phone, customer_name: name, address: addr, type, remark, discount, discount_type: discountType, payments: lines.map((l) => ({ method: l.method, custom_method: l.custom, amount: l.amount })) }) })
      onDone()
    } catch (x) { setErr(x.message) }
  }
  return (
    <Modal width={520} onClose={onClose}>
      <form onSubmit={submit}>
        <h3>{type === 'receipt' ? '收款' : '退款'}</h3>
        <div className="row">客户电话：<input value={phone} maxLength={30} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); lookup() } }} onChange={(e) => { setPhone(e.target.value); setMatches([]) }} placeholder="电话 / 姓名 / 地址关键字" /> <button type="button" className="secondary" disabled={busy || !phone} onClick={lookup}>{busy ? '查询中…' : '查客户'}</button></div>
        {looked && (<>
          <div className="row">客户姓名：<input value={name} onChange={(e) => setName(e.target.value)} /></div>
          <div className="row">客户地址：<input value={addr} onChange={(e) => setAddr(e.target.value)} /></div>
        </>)}
        <table>
          <thead><tr><th>付款方式</th><th>自定义方式</th><th>金额</th><th></th></tr></thead>
          <tbody>{lines.map((l, i) => (
            <tr key={i}>
              <td><select value={l.method} onChange={(e) => setLine(i, { method: e.target.value })}>
                {(presets.pay.length ? presets.pay : ['其他']).map((m) => <option key={m} value={m}>{m}</option>)}
              </select></td>
              <td>{l.method === '其他' ? <input value={l.custom} onChange={(e) => setLine(i, { custom: e.target.value })} placeholder="自定义方式" /> : '—'}</td>
              <td><input value={l.amount} placeholder={type === 'refund' && refundable != null ? `可退${refundable}` : ''} onChange={(e) => setLine(i, { amount: e.target.value })} /></td>
              <td>
                <button type="button" className="secondary" onClick={() => setLines((ls) => { const a = [...ls]; if (i > 0) { [a[i - 1], a[i]] = [a[i], a[i - 1]] } return a })}>↑</button>
                <button type="button" className="secondary" onClick={() => setLines((ls) => { const a = [...ls]; if (i < a.length - 1) { [a[i + 1], a[i]] = [a[i], a[i + 1]] } return a })}>↓</button>
                <button type="button" className="secondary" onClick={() => setLines((ls) => ls.filter((_, k) => k !== i))}>删</button>
              </td>
            </tr>
          ))}</tbody>
        </table>
        <button type="button" className="secondary" onClick={() => setLines((ls) => [...ls, { method: '微信', custom: '', amount: '' }])}>+ 添加付款方式</button>
        <div className="row">合计：<b>{total.toFixed(2)}</b></div>
        {type === 'receipt' && outstanding != null && (
          <div className="muted">应收金额：{outstanding}　实收：{total.toFixed(2)}　优惠：{Number(discount || 0).toFixed(2)}　
            {Number(discount || 0) + total >= Number(outstanding) ? '本单可结清' : '仍有未收'}
          </div>
        )}
        {type === 'receipt' && (
          <div className="row">
            优惠方式：<select value={discountType} onChange={(e) => setDiscountType(e.target.value)}>
              <option value="">（无）</option>
              {presets.disc.map((d) => <option key={d} value={d}>{d}</option>)}
            </select>
            优惠金额：<input style={{ width: 100 }} value={discount} onChange={(e) => setDiscount(e.target.value)} />
          </div>
        )}
        {type === 'refund' && refundable != null && <div className="muted">可退金额：{refundable}{Number(refundable) === 0 ? '（该客户无可退金额：未收款或已退完）' : ''}</div>}
        <div className="row">备注：<input value={remark} onChange={(e) => setRemark(e.target.value)} /></div>
        {err && <div className="err">{err}</div>}
        {matches.length > 0 && (
          <div className="row" style={{ flexWrap: 'wrap' }}>
            {matches.map((c) => <button key={c.id} type="button" className="secondary" onClick={() => applyCustomer(c)}>{c.name} / {c.phone} / {c.addr || '无地址'}</button>)}
          </div>
        )}
        <div style={{ marginTop: 10 }}><button disabled={type === 'refund' && refundable != null && Number(refundable) <= 0}>提交</button> <button type="button" className="secondary" onClick={onClose}>取消</button></div>
      </form>
    </Modal>
  )
}

function NoteModal({ onClose, onDone }) {
  const [phone, setPhone] = useState('')
  const [orders, setOrders] = useState([])
  const [ref, setRef] = useState(null)
  const [remark, setRemark] = useState('')
  const [err, setErr] = useState('')
  const search = async () => {
    try { setOrders(await api('/api/orders/recent?phone=' + encodeURIComponent(phone) + '&days=365')) } catch (e) { setErr(e.message) }
  }
  const pick = (no) => {
    const o = orders.find((x) => x.order_no === no) || null
    setRef(o)
    setRemark(o?.remark || '')
  }
  const submit = async (e) => {
    e.preventDefault()
    try {
      if (!ref) throw new Error('请选择要备注的单据')
      await api('/api/orders/' + ref.id + '/remark', { method: 'POST', body: JSON.stringify({ remark }) })
      onDone()
    } catch (x) { setErr(x.message) }
  }
  return (
    <Modal width={640} onClose={onClose}>
      <form onSubmit={submit}>
        <h3>备注（写入选中的单据）</h3>
        <div className="row">客户电话：<input value={phone} onChange={(e) => setPhone(e.target.value)} /> <button type="button" className="secondary" onClick={search}>查单据</button></div>
        {orders.length > 0 && (
          <div className="row">选择单据：<select value={ref?.order_no || ''} onChange={(e) => pick(e.target.value)}>
            <option value="">请选择</option>
            {orders.map((o) => <option key={o.id} value={o.order_no}>{o.order_no} {typeLabel[o.type]} {amountLabel(o.type)} {Math.abs(Number(o.order_total)).toFixed(2)}</option>)}
          </select></div>
        )}
        {ref && <p className="muted">原备注：{ref.remark || '（空）'}</p>}
        <div className="row">备注：<textarea value={remark} onChange={(e) => setRemark(e.target.value)} rows={4} style={{ width: '100%' }} /></div>
        {err && <div className="err">{err}</div>}
        <div style={{ marginTop: 10 }}><button>保存备注</button> <button type="button" className="secondary" onClick={onClose}>取消</button></div>
      </form>
    </Modal>
  )
}

function Dashboard() {
  const [rows, setRows] = useState([])
  const [detail, setDetail] = useState(null)
  const [modal, setModal] = useState(null)
  const load = useCallback(() => api('/api/orders/today').then(setRows).catch(() => {}), [])
  useEffect(() => { load() }, [load])
  const del = async (id) => { if (confirm('确认删除该单据？')) { await api('/api/orders/' + id, { method: 'DELETE' }); load() } }
  const archive = async (id) => { if (confirm('归档后不可修改/删除，确认？')) { await api('/api/orders/' + id + '/archive', { method: 'POST' }); load() } }
  return (
    <>
      <h2>仪表盘 · 今日单据（{rows.length}）</h2>
      <div style={{ marginBottom: 12 }}>
        <button onClick={() => setModal('delivery')}>配送</button>
        <button onClick={() => setModal('delivery_return')}>配退</button>
        <button onClick={() => setModal('pickup')}>自送</button>
        <button onClick={() => setModal('pickup_return')}>自退</button>
        <button className="secondary" onClick={() => setModal('note')}>备注</button>
        <button onClick={() => setModal('receipt')}>收款</button>
        <button className="secondary" onClick={() => setModal('refund')}>退款</button>
      </div>
      <OrderTable rows={rows} onDetail={(id, edit) => setDetail({ id, edit })} onDelete={del} onArchive={archive} />
      {detail && <OrderDetailModal id={detail.id} startEdit={detail.edit} onClose={() => setDetail(null)} onChanged={load} />}
      {modal && modal !== 'note' && modal !== 'receipt' && modal !== 'refund' && <OrderModal type={modal} onClose={() => setModal(null)} onDone={() => { setModal(null); load() }} />}
      {(modal === 'receipt' || modal === 'refund') && <PaymentModal type={modal} onClose={() => setModal(null)} onDone={() => { setModal(null); load() }} />}
      {modal === 'note' && <NoteModal onClose={() => setModal(null)} onDone={() => { setModal(null); load() }} />}
    </>
  )
}

function Orders({ isAdmin }) {
  const [rows, setRows] = useState([])
  const [detail, setDetail] = useState(null)
  const load = useCallback(() => api('/api/orders/all').then(setRows).catch(() => {}), [])
  useEffect(() => { load() }, [load])
  const del = async (id) => { if (confirm('确认删除该单据？')) { await api('/api/orders/' + id, { method: 'DELETE' }); load() } }
  const archive = async (id) => { if (confirm('归档后不可修改/删除，确认？')) { await api('/api/orders/' + id + '/archive', { method: 'POST' }); load() } }
  return (
    <>
      <h2>单据库（全部 {rows.length} 张）</h2>
      <OrderTable rows={rows} canEdit={isAdmin} onDetail={(id, edit) => setDetail({ id, edit })} onDelete={del} onArchive={archive} />
      {detail && <OrderDetailModal id={detail.id} startEdit={detail.edit} onClose={() => setDetail(null)} onChanged={load} />}
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

const emptyCat = { group: '', name: '', batch: '', settlement_unit: '', conversion_unit: '', conversion: '1', default_price: '' }

function usePresets() {
  const [p, setP] = useState({ s: [], c: [], pay: [], disc: [] })
  useEffect(() => {
    api('/api/settings').then((st) => setP({
      s: (st.settlement_units || '').split(',').map((x) => x.trim()).filter(Boolean),
      c: (st.conversion_units || '').split(',').map((x) => x.trim()).filter(Boolean),
      pay: (st.payment_methods || '').split(',').map((x) => x.trim()).filter(Boolean),
      disc: (st.discount_types || '').split(',').map((x) => x.trim()).filter(Boolean),
    })).catch(() => {})
  }, [])
  return p
}

function CategoryModal({ init, onClose, onDone }) {
  const [f, setF] = useState(init || emptyCat)
  const [err, setErr] = useState('')
  const upper = (v) => v.replace(/[a-z]/g, (c) => c.toUpperCase())
  const presets = usePresets()
  const set = (k) => (e) => setF({ ...f, [k]: ['username', 'name'].includes(k) ? upper(e.target.value) : e.target.value })
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
    <Modal width={720} onClose={onClose}>
      <form onSubmit={save}>
        <h3>{init?.id ? '编辑型号' : '新增型号'}</h3>
        <label className="row">一级 品类：<input value={f.group} onChange={set('group')} required /></label>
        <label className="row">二级 型号：<input value={f.name} onChange={set('name')} required /></label>
        <label className="row">三级 批号（可空，保存时默认等于型号）：<input value={f.batch} onChange={set('batch')} placeholder="留空则自动用型号作为批号" /></label>
        <label className="row">四级 计价单位：<input list="unit-s" value={f.settlement_unit} onChange={set('settlement_unit')} required /><datalist id="unit-s">{presets.s.map((u) => <option key={u} value={u} />)}</datalist></label>
        <label className="row">默认价格（元/计价单位）：<input value={f.default_price} onChange={set('default_price')} placeholder="可空，新客户默认用它" /></label>
        <label className="row">五级 换算单位（可选）：<input value={f.conversion_unit} onChange={set('conversion_unit')} /></label>
        {f.conversion_unit.trim() !== '' && (
          <div className="row">换算系数：1 <b>{f.conversion_unit}</b> = <input style={{ width: 70 }} value={f.conversion} onChange={set('conversion')} /> <b>{f.settlement_unit || '暂无'}</b></div>
        )}
        {err && <div className="err">{err}</div>}
        <button>保存</button> <button type="button" className="secondary" onClick={onClose}>取消</button>
      </form>
    </Modal>
  )
}

function Categories() {
  const [rows, setRows] = useState([])
  const [edit, setEdit] = useState(undefined)
  const load = useCallback(() => api('/api/categories').then((list) => {
    const key = (g) => (/^\d/.test(g) ? '0' + g : '1' + g)
    setRows([...list].sort((a, b) => key(a.group).localeCompare(key(b.group), 'zh-Hans-CN') || a.id - b.id))
  }).catch(() => {}), [])
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
        <thead><tr><th>品类</th><th>型号</th><th>批号</th><th>计价单位</th><th>换算单位</th><th>默认价</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{rows.map((c) => (
          <tr key={c.id}>
            <td>{c.group}</td><td>{c.name}</td><td>{c.batch || '无'}</td><td>{c.settlement_unit}</td>
            <td>{c.conversion_unit ? `${c.conversion_unit}（1${c.conversion_unit}=${c.conversion}${c.settlement_unit}）` : '无'}</td>
            <td>{c.default_price || '无'}</td>
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
  const upper = (v) => v.replace(/[a-z]/g, (c) => c.toUpperCase())
  const presets = usePresets()
  const set = (k) => (e) => setF({ ...f, [k]: ['username', 'name'].includes(k) ? upper(e.target.value) : e.target.value })
  const save = async (e) => {
    e.preventDefault()
    try {
      if (init?.id) {
        await api('/api/accounts/' + init.id, { method: 'PUT', body: JSON.stringify({ name: f.name, role: String(f.role).toLowerCase(), status: String(f.status).toLowerCase(), password: f.password }) })
      } else {
        await api('/api/accounts', { method: 'POST', body: JSON.stringify({ ...f, role: String(f.role).toLowerCase(), status: String(f.status).toLowerCase() }) })
      }
      onDone()
    } catch (x) { setErr(x.message) }
  }
  return (
    <Modal width={720} onClose={onClose}>
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
    </Modal>
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

function SettlementDetail({ id, onClose }) {
  const [d, setD] = useState(null)
  const [orderId, setOrderId] = useState(null)
  useEffect(() => { api('/api/settlement/' + id).then(setD).catch(() => {}) }, [id])
  return (
    <>
    <Modal width={760} onClose={onClose}>
      {!d ? <p>加载中…</p> : (
        <>
          <h3>结算详情 · {d.customer.name}</h3>
          <p>电话：{d.customer.phone}　地址：{d.customer.addr || '无'}</p>
          <p>送货合计：<b>{d.receivable}</b>　退货合计：<b>{d.payable}</b>　收款合计：<b>{d.received}</b>　退款合计：<b>{d.refunded}</b></p>
          <p style={{ fontSize: 16 }}>
            {Number(d.outstanding) > 0 && <>现应收：<b>{Math.abs(Number(d.outstanding)).toFixed(2)}</b></>}
            {Number(d.outstanding) < 0 && <>现应退：<b>{Math.abs(Number(d.outstanding)).toFixed(2)}</b></>}
            {Number(d.outstanding) === 0 && <b>已结清</b>}
          </p>
          <table>
            <thead><tr><th>单号</th><th>类型</th><th>日期</th><th>金额</th><th>操作</th></tr></thead>
            <tbody>{d.orders.map((o) => (
              <tr key={o.id}><td>{o.order_no}</td><td>{typeLabel[o.type] || o.type}</td>
                <td>{o.created_at?.slice(0, 10)}</td>
                <td style={{ color: o.order_total.startsWith('-') ? '#c62828' : '#2e7d32' }}>{amountLabel(o.type)} {Math.abs(Number(o.order_total)).toFixed(2)}</td>
                <td><button className="secondary" onClick={() => setOrderId(o.id)}>详情</button></td></tr>
            ))}</tbody>
          </table>
          <div style={{ marginTop: 10 }}><button className="secondary" onClick={onClose}>关闭</button></div>
        </>
      )}
    </Modal>
    {orderId && <OrderDetailModal id={orderId} onClose={() => setOrderId(null)} />}
    </>
  )
}

function Settlement() {
  const [rows, setRows] = useState([])
  const [detail, setDetail] = useState(null)
  useEffect(() => { api('/api/settlement').then(setRows).catch(() => {}) }, [])
  return (
    <>
      <h2>结算汇总（按客户）</h2>
      <table>
        <thead><tr><th>电话</th><th>客户</th><th>累计结算</th><th>最近交易</th><th>操作</th></tr></thead>
        <tbody>{rows.map((r) => (
          <tr key={r.id}>
            <td>{r.phone}</td><td>{r.name}</td><td>{r.total}</td><td>{r.last?.slice(0, 10)}</td>
            <td><button className="secondary" onClick={() => setDetail(r.id)}>详情</button></td>
          </tr>
        ))}</tbody>
      </table>
      {detail && <SettlementDetail id={detail} onClose={() => setDetail(null)} />}
    </>
  )
}

function PresetEditor({ label, value, onChange }) {
  const items = value ? value.split(',').map((x) => x.trim()).filter(Boolean) : []
  const [draft, setDraft] = useState('')
  const set = (arr) => onChange(arr.join(','))
  const move = (i, d) => {
    const a = [...items]; const j = i + d
    if (j < 0 || j >= a.length) return
    ;[a[i], a[j]] = [a[j], a[i]]
    set(a)
  }
  return (
    <div style={{ margin: '8px 0' }}>
      <div>{label}：</div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, margin: '4px 0' }}>
        {items.map((it, i) => (
          <span key={it + i} style={{ border: '1px solid #cfd6df', borderRadius: 4, padding: '2px 6px' }}>
            {i + 1}. {it}
            <button type="button" className="secondary" style={{ marginLeft: 4 }} onClick={() => move(i, -1)}>↑</button>
            <button type="button" className="secondary" onClick={() => move(i, 1)}>↓</button>
            <button type="button" className="danger" onClick={() => set(items.filter((_, k) => k !== i))}>×</button>
          </span>
        ))}
        {items.length === 0 && <span className="muted">（尚未设置）</span>}
      </div>
      <input value={draft} placeholder="输入后回车添加" onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && draft.trim()) { e.preventDefault(); set([...items, draft.trim()]); setDraft('') } }} />
      <button type="button" className="secondary" style={{ marginLeft: 6 }} onClick={() => { if (draft.trim()) { set([...items, draft.trim()]); setDraft('') } }}>添加</button>
    </div>
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
      <p>API 端口：<input value={s.api_port || '9527'} onChange={(e) => setS({ ...s, api_port: e.target.value })} /></p>
      <PresetEditor label="计价单位预设（顺序即选择顺序）" value={s.settlement_units || ''} onChange={(v) => setS({ ...s, settlement_units: v })} />
      <PresetEditor label="换算单位预设（顺序即选择顺序）" value={s.conversion_units || ''} onChange={(v) => setS({ ...s, conversion_units: v })} />
      <PresetEditor label="付款方式预设（顺序即选择顺序）" value={s.payment_methods || ''} onChange={(v) => setS({ ...s, payment_methods: v })} />
      <PresetEditor label="优惠方式预设（顺序即选择顺序）" value={s.discount_types || ''} onChange={(v) => setS({ ...s, discount_types: v })} />
      <p>小票宽度：<select value={s.print_width || '80mm'} onChange={(e) => setS({ ...s, print_width: e.target.value })}><option>80mm</option><option>58mm</option></select></p>
      <button onClick={async () => { await api('/api/settings', { method: 'POST', body: JSON.stringify(s) }); setMsg('已保存') }}>保存</button>
      {msg && <span style={{ marginLeft: 8 }}>{msg}</span>}
      <p className="muted">默认端口 9527；修改后需重启应用生效，客户端需同步更新地址。</p>
    </>
  )
}

const TABS = [['dashboard', '仪表盘'], ['orders', '单据'], ['print', '打印'], ['categories', '型号库'], ['accounts', '账号'], ['settlement', '结算'], ['settings', '配置']]
const visibleTabs = (isAdmin) => TABS.filter(([k]) => k !== 'accounts' || isAdmin)

export default function App() {
  const [acc, setAcc] = useState(() => {
    const token = localStorage.getItem('czxs_token')
    return token ? { name: localStorage.getItem('czxs_name') || '用户', role: localStorage.getItem('czxs_role') || 'staff' } : null
  })
  const [tab, setTab] = useState('dashboard')
  const isAdmin = acc?.role === 'admin'
  const nav = useMemo(() => visibleTabs(isAdmin), [isAdmin])
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
        {tab === 'orders' && <Orders isAdmin={isAdmin} />}
        {tab === 'print' && <PrintPage />}
        {tab === 'categories' && <Categories />}
        {tab === 'accounts' && <Accounts isAdmin={isAdmin} />}
        {tab === 'settlement' && <Settlement />}
        {tab === 'settings' && <Settings />}
      </main>
    </>
  )
}
