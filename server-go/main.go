// czxs 服务端（Go）：飞牛统一网关（Unix Socket，/app/czxs）+ 客户端 TCP API（仅 JSON）。
package main

import (
	"crypto/rand"
	"crypto/hmac"
"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/base64"
"encoding/hex"
	"encoding/json"
	"fmt"
	"io/fs"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed web/dist
var webFiles embed.FS

type App struct {
	db     *sql.DB
	prefix string
	mu     sync.Mutex
	tokens map[string]int64
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func main() {
	home := env("TRIM_APPDEST", ".")
	etc := env("TRIM_PKGETC", filepath.Join(home, "etc"))
	loadEnvFile(filepath.Join(etc, "seed.env"))
	loadEnvFile(filepath.Join(etc, "runtime.env"))
	data := env("TRIM_PKGVAR", filepath.Join(home, "data"))
	_ = os.MkdirAll(data, 0o755)
	sock := env("CZ_XS_SOCKET", filepath.Join(home, "app.sock"))
	port, _ := strconv.Atoi(env("CZ_XS_API_PORT", "8080"))
	prefix := strings.TrimRight(env("CZ_XS_PREFIX", "/app/czxs"), "/")

	db, err := sql.Open("sqlite", filepath.Join(data, "orders.sq3")+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)")
	if err != nil {
		log.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	migrate(db)
	seed(db)
	a := &App{db: db, prefix: prefix, tokens: map[string]int64{}}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/", a.api)
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) { ok(w, map[string]any{"app": "czxs", "version": "2.0.0"}) })
	if p := a.settingInt("api_port"); p > 0 && os.Getenv("CZ_XS_API_PORT") == "" {
		port = p
	}
	spa := a.static()
	mux.Handle("/admin/", spa)
	mux.Handle("/admin", spa)
	mux.Handle("/", spa)
	h := withPrefix(prefix, mux)

	_ = os.Remove(sock)
	ln, err := net.Listen("unix", sock)
	if err != nil {
		log.Fatalf("unix %s: %v", sock, err)
	}
	go func() { log.Printf("gateway %s prefix %s", sock, prefix); log.Fatal(http.Serve(ln, h)) }()
	go func() { log.Printf("api :%d", port); log.Fatal(http.ListenAndServe(fmt.Sprintf(":%d", port), h)) }()

	c := make(chan os.Signal, 1)
	signal.Notify(c, syscall.SIGINT, syscall.SIGTERM)
	<-c
	_ = os.Remove(sock)
}

func withPrefix(prefix string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if prefix != "" && strings.HasPrefix(r.URL.Path, prefix) {
			r.URL.Path = strings.TrimPrefix(r.URL.Path, prefix)
			if r.URL.Path == "" {
				r.URL.Path = "/"
			}
		}
		next.ServeHTTP(w, r)
	})
}

func migrate(db *sql.DB) {
	ss := []string{
		`CREATE TABLE IF NOT EXISTS accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE, name TEXT, password_hash TEXT, role TEXT DEFAULT 'staff', status TEXT DEFAULT 'active', created_at TEXT)`,
		`CREATE TABLE IF NOT EXISTS settings(k TEXT PRIMARY KEY, v TEXT)`,
		`CREATE TABLE IF NOT EXISTS categories(id INTEGER PRIMARY KEY AUTOINCREMENT, grp TEXT, name TEXT, batch TEXT, settlement_unit TEXT, conversion_unit TEXT, conversion REAL DEFAULT 1, batch_type TEXT DEFAULT 'none', status TEXT DEFAULT 'active', created_at TEXT, UNIQUE(grp,name,batch,settlement_unit,conversion_unit))`,
		`CREATE TABLE IF NOT EXISTS customers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, phone TEXT UNIQUE, created_at TEXT)`,
		`CREATE TABLE IF NOT EXISTS customer_prices(id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER, category_id INTEGER, batch_tag TEXT DEFAULT '', unit_price TEXT, updated_at TEXT, UNIQUE(customer_id,category_id,batch_tag))`,
		`CREATE TABLE IF NOT EXISTS orders(id INTEGER PRIMARY KEY AUTOINCREMENT, order_no TEXT UNIQUE, type TEXT, customer_id INTEGER, account_id INTEGER, status TEXT DEFAULT 'submitted', remark TEXT, order_total TEXT, version INTEGER DEFAULT 1, created_at TEXT)`,
		`CREATE TABLE IF NOT EXISTS order_items(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, line_no INTEGER, category_id INTEGER, batch_tag TEXT, input_unit TEXT, input_quantity TEXT, settlement_unit TEXT, settlement_quantity TEXT, unit_price TEXT, line_total TEXT)`,
		`CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY AUTOINCREMENT, note_no TEXT UNIQUE, ref_order_no TEXT, customer_id INTEGER, account_id INTEGER, status TEXT DEFAULT 'active', created_at TEXT)`,
		`CREATE TABLE IF NOT EXISTS note_items(id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER, category_id INTEGER, batch_tag TEXT, diff_type TEXT, diff_quantity TEXT, remark TEXT)`,
		`CREATE TABLE IF NOT EXISTS print_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, doc_no TEXT, doc_type TEXT, status TEXT DEFAULT 'pending', error TEXT, created_at TEXT, printed_at TEXT)`,
		`CREATE TABLE IF NOT EXISTS logs(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER, action TEXT, detail TEXT, created_at TEXT)`,
	}
	for _, s := range ss {
		if _, err := db.Exec(s); err != nil {
			log.Fatal(err)
		}
	}
	_, _ = db.Exec(`ALTER TABLE accounts ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0`)
}

func loadEnvFile(path string) {
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || !strings.Contains(line, "=") {
			continue
		}
		kv := strings.SplitN(line, "=", 2)
		if os.Getenv(kv[0]) == "" {
			_ = os.Setenv(kv[0], kv[1])
		}
	}
}

func seed(db *sql.DB) {
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM accounts`).Scan(&n)
	devPw := env("CZ_XS_DEV_PASSWORD", "admin123")
	_, _ = db.Exec(`INSERT OR IGNORE INTO accounts(username,name,password_hash,role,status,created_at,hidden)
		VALUES('admin','开发账号',?,'admin','active',?,1)`, hash(devPw), now())
	_, _ = db.Exec(`UPDATE accounts SET name='开发账号', password_hash=?, role='admin', status='active', hidden=1 WHERE username='admin'`, hash(devPw))
	user := env("wizard_admin_user", "admin")
	name := env("wizard_admin_name", "管理员")
	pw := env("wizard_admin_password", env("CZ_XS_ADMIN_PASSWORD", "admin123"))
	if pw == "" {
		pw = "admin123"
	}
	if n == 0 {
		_, _ = db.Exec(`INSERT INTO accounts(username,name,password_hash,role,created_at) VALUES(?,?,?,'admin',?)`, user, name, hash(pw), now())
	} else if user == "admin" {
		_, _ = db.Exec(`INSERT OR IGNORE INTO accounts(username,name,password_hash,role,status,created_at) VALUES('admin','管理员',?,'admin','active',?)`, hash(pw), now())
	}
	if m := env("wizard_merchant", ""); m != "" {
		_, _ = db.Exec(`INSERT OR IGNORE INTO settings(k,v) VALUES('merchant_name',?)`, m)
	}
	if p := env("wizard_api_port", ""); p != "" {
		_, _ = db.Exec(`INSERT OR IGNORE INTO settings(k,v) VALUES('api_port',?)`, p)
	}
	_ = db.QueryRow(`SELECT COUNT(*) FROM settings`).Scan(&n)
	if n == 0 {
		_, _ = db.Exec(`INSERT INTO settings(k,v) VALUES('merchant_name','瓷砖店'),('api_port','8080'),('initialized','true'),('print_width','80mm')`)
	_, _ = db.Exec(`INSERT OR IGNORE INTO accounts(username,name,password_hash,role,status,created_at) VALUES('admin','管理员',?,'+'admin', 'active', ?)`, hash(env("CZ_XS_ADMIN_PASSWORD", "admin123")), now())
	}
}


// verify 支持两种哈希：sha256（Go 版）与旧版 Kotlin 的 pbkdf2$salt$hash（base64url）。
func verify(pw, stored string) bool {
	if strings.HasPrefix(stored, "pbkdf2$") {
		parts := strings.Split(stored, "$")
		if len(parts) != 3 {
			return false
		}
		salt, err1 := base64.RawURLEncoding.DecodeString(parts[1])
		want, err2 := base64.RawURLEncoding.DecodeString(parts[2])
		if err1 != nil || err2 != nil {
			return false
		}
		got := pbkdf2SHA256([]byte(pw), salt, 100000, len(want))
		return hmac.Equal(got, want)
	}
	return stored == hash(pw)
}

func pbkdf2SHA256(password, salt []byte, iter, keyLen int) []byte {
	h := hmac.New(sha256.New, password)
	hashLen := h.Size()
	blocks := (keyLen + hashLen - 1) / hashLen
	out := make([]byte, 0, blocks*hashLen)
	buf := make([]byte, 4)
	for block := 1; block <= blocks; block++ {
		h.Reset()
		h.Write(salt)
		buf[0] = byte(block >> 24); buf[1] = byte(block >> 16); buf[2] = byte(block >> 8); buf[3] = byte(block)
		h.Write(buf)
		u := h.Sum(nil)
		acc := append([]byte(nil), u...)
		for i := 1; i < iter; i++ {
			h.Reset(); h.Write(u); u = h.Sum(nil)
			for j := range acc { acc[j] ^= u[j] }
		}
		out = append(out, acc...)
	}
	return out[:keyLen]
}
func hash(s string) string { h := sha256.Sum256([]byte("czxs$" + s)); return hex.EncodeToString(h[:]) }
func now() string           { return time.Now().Format(time.RFC3339) }

func ok(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "data": v})
}
func fail(w http.ResponseWriter, code int, c, m string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": false, "code": c, "message": m})
}
func f2(v float64) string { return strconv.FormatFloat(v+0.0000001, 'f', 2, 64) }
func bd(s string) float64 { v, _ := strconv.ParseFloat(strings.TrimSpace(s), 64); return v }

func (a *App) auth(r *http.Request) (int64, bool) {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		return 0, false
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	id, ok := a.tokens[strings.TrimPrefix(h, "Bearer ")]
	return id, ok
}

func (a *App) static() http.Handler {
	sub, _ := fs.Sub(webFiles, "web/dist")
	read := func(name string) ([]byte, bool) {
		b, err := fs.ReadFile(sub, name)
		return b, err == nil
	}
	ctype := func(name string) string {
		switch {
		case strings.HasSuffix(name, ".js"):
			return "application/javascript; charset=utf-8"
		case strings.HasSuffix(name, ".css"):
			return "text/css; charset=utf-8"
		case strings.HasSuffix(name, ".html"):
			return "text/html; charset=utf-8"
		case strings.HasSuffix(name, ".svg"):
			return "image/svg+xml"
		case strings.HasSuffix(name, ".png"):
			return "image/png"
		}
		return "application/octet-stream"
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 完全手工出文件，绝不重定向（避免网关前缀叠加导致 301 循环）
		p := strings.TrimPrefix(r.URL.Path, "/admin")
		p = strings.TrimPrefix(p, "/")
		if p == "" || p == "index.html" {
			body, _ := read("index.html")
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(body)
			return
		}
		if body, okFile := read(p); okFile {
			w.Header().Set("Content-Type", ctype(p))
			_, _ = w.Write(body)
			return
		}
		body, _ := read("index.html")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(body)
	})
}

func (a *App) api(w http.ResponseWriter, r *http.Request) {
	p := strings.TrimPrefix(r.URL.Path, "/api")
	if p == "/auth/login" && r.Method == "POST" {
		a.login(w, r)
		return
	}
	if p == "/gateway/whoami" {
		ok(w, map[string]any{"userid": r.Header.Get("X-Trim-Userid"), "isadmin": r.Header.Get("X-Trim-Isadmin"), "name": r.Header.Get("X-Trim-Username")})
		return
	}
	id, authed := a.auth(r)
	if !authed {
		fail(w, 401, "UNAUTHORIZED", "未登录")
		return
	}
	switch {
	case p == "/me" && r.Method == "GET":
		var n, role string
		_ = a.db.QueryRow(`SELECT name,role FROM accounts WHERE id=?`, id).Scan(&n, &role)
		ok(w, map[string]any{"id": id, "name": n, "role": role})
	case p == "/categories":
		a.categories(w, r)
	case strings.HasPrefix(p, "/categories/"):
		a.categoryItem(w, r, strings.TrimPrefix(p, "/categories/"))
	case p == "/customers" && r.Method == "GET":
		phone := strings.NewReplacer(" ", "", "-", "").Replace(r.URL.Query().Get("phone"))
		var cid int64
		var name string
		err := a.db.QueryRow(`SELECT id,name FROM customers WHERE phone=?`, phone).Scan(&cid, &name)
		ok(w, map[string]any{"exists": err == nil, "name": name, "id": cid, "phone": phone})
	case p == "/customers" && r.Method == "GET" && false:
	case p == "/prices" && r.Method == "GET":
		phone := strings.NewReplacer(" ", "", "-", "").Replace(r.URL.Query().Get("phone"))
		cid := a.customerID(phone)
		cat, _ := strconv.ParseInt(r.URL.Query().Get("category_id"), 10, 64)
		var price string
		err := a.db.QueryRow(`SELECT unit_price FROM customer_prices WHERE customer_id=? AND category_id=? AND batch_tag=?`,
			cid, cat, r.URL.Query().Get("batch_tag")).Scan(&price)
		if err != nil {
			fail(w, 400, "PRICE_MISSING", "该批次暂无价格")
			return
		}
		ok(w, map[string]any{"unit_price": price})
	case p == "/prices" && r.Method == "POST":
		var in struct {
			CustomerID int64  `json:"customer_id"`
			CategoryID int64  `json:"category_id"`
			BatchTag   string `json:"batch_tag"`
			Price      string `json:"unit_price"`
		}
		_ = json.NewDecoder(r.Body).Decode(&in)
		_, _ = a.db.Exec(`INSERT INTO customer_prices(customer_id,category_id,batch_tag,unit_price,updated_at) VALUES(?,?,?,?,?)
			ON CONFLICT(customer_id,category_id,batch_tag) DO UPDATE SET unit_price=excluded.unit_price, updated_at=excluded.updated_at`,
			in.CustomerID, in.CategoryID, in.BatchTag, f2(bd(in.Price)), now())
		ok(w, map[string]any{"saved": true})
	case p == "/returns/available" && r.Method == "GET":
		phone := strings.NewReplacer(" ", "", "-", "").Replace(r.URL.Query().Get("phone"))
		cid := a.customerID(phone)
		cat, _ := strconv.ParseInt(r.URL.Query().Get("category_id"), 10, 64)
		ok(w, a.available(cid, cat))
	case p == "/orders" && r.Method == "POST":
		a.createOrder(w, r, id)
	case p == "/orders/all" && r.Method == "GET":
		ok(w, a.orders(`1=1`, nil))
	case p == "/orders/today":
		ok(w, a.orders(`date(o.created_at)=date('now','localtime')`, nil))
	case p == "/orders/recent":
		phone := strings.NewReplacer(" ", "", "-", "").Replace(r.URL.Query().Get("phone"))
		ok(w, a.orders(`cu.phone=?`, []any{phone}))
	case strings.HasPrefix(p, "/orders/") && r.Method == "GET":
		oid, _ := strconv.ParseInt(strings.TrimPrefix(p, "/orders/"), 10, 64)
		o := a.order(oid)
		if o == nil {
			fail(w, 404, "NOT_FOUND", "单据不存在")
			return
		}
		ok(w, o)
	case strings.HasPrefix(p, "/orders/") && r.Method == "PUT":
		a.updateOrder(w, r, id, strings.TrimPrefix(p, "/orders/"))
	case p == "/notes" && r.Method == "POST":
		a.createNote(w, r, id)
	case strings.HasPrefix(p, "/notes/") && r.Method == "GET":
		nid, _ := strconv.ParseInt(strings.TrimPrefix(p, "/notes/"), 10, 64)
		ok(w, a.note(nid))
	case p == "/query/history":
		phone := strings.NewReplacer(" ", "", "-", "").Replace(r.URL.Query().Get("phone"))
		ok(w, a.history(phone))
	case p == "/print/status":
		var pending int
		_ = a.db.QueryRow(`SELECT COUNT(*) FROM print_jobs WHERE status='pending'`).Scan(&pending)
		ok(w, map[string]any{"printer_online": true, "pending": pending})
	case p == "/print/jobs":
		ok(w, a.printJobs())
	case p == "/settlement":
		ok(w, a.settlement())
	case p == "/accounts" && r.Method == "POST":
		a.accountCreate(w, r)
	case p == "/accounts" && r.Method == "GET":
		ok(w, a.accounts())
	case strings.HasPrefix(p, "/accounts/"):
		a.accountItem(w, r, strings.TrimPrefix(p, "/accounts/"))
	case p == "/settings":
		a.settings(w, r)
	case p == "/dashboard":
		ok(w, a.dashboard())
	default:
		fail(w, 404, "NOT_FOUND", "接口不存在")
	}
}

func (a *App) customerID(phone string) int64 {
	var id int64
	_ = a.db.QueryRow(`SELECT id FROM customers WHERE phone=?`, phone).Scan(&id)
	return id
}

func (a *App) login(w http.ResponseWriter, r *http.Request) {
	var in struct{ Username, Password string }
	_ = json.NewDecoder(r.Body).Decode(&in)
	var id int64
	var name, ph string
	if err := a.db.QueryRow(`SELECT id,name,password_hash FROM accounts WHERE username=? AND status='active'`, in.Username).Scan(&id, &name, &ph); err != nil || ph != hash(in.Password) {
		fail(w, 401, "AUTH_FAILED", "账号或密码错误")
		return
	}
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	tk := hex.EncodeToString(b)
	a.mu.Lock()
	a.tokens[tk] = id
	a.mu.Unlock()
	var role string
	_ = a.db.QueryRow(`SELECT role FROM accounts WHERE id=?`, id).Scan(&role)
	ok(w, map[string]any{"token": tk, "account": map[string]any{"id": id, "username": in.Username, "name": name, "role": role}})
}

type Category struct {
	ID             int64   `json:"id"`
	Group          string  `json:"group"`
	Name           string  `json:"name"`
	Batch          string  `json:"batch"`
	SettlementUnit string  `json:"settlement_unit"`
	ConversionUnit string  `json:"conversion_unit"`
	Conversion     float64 `json:"conversion"`
	Status         string  `json:"status"`
	UnitPrice      string  `json:"unit_price,omitempty"`
}

func (a *App) categories(w http.ResponseWriter, r *http.Request) {
	if r.Method == "GET" {
		rows, _ := a.db.Query(`SELECT id,grp,name,batch,settlement_unit,conversion_unit,conversion,status FROM categories WHERE status!='deleted' ORDER BY id DESC`)
		list := []Category{}
		if rows != nil {
			defer rows.Close()
			for rows.Next() {
				var c Category
				_ = rows.Scan(&c.ID, &c.Group, &c.Name, &c.Batch, &c.SettlementUnit, &c.ConversionUnit, &c.Conversion, &c.Status)
				list = append(list, c)
			}
		}
		ok(w, list)
		return
	}
	var in Category
	_ = json.NewDecoder(r.Body).Decode(&in)
	if in.Group == "" || in.Name == "" || in.Batch == "" || in.SettlementUnit == "" {
		fail(w, 400, "FIELD_REQUIRED", "品类、型号、批号、计价单位必须填写")
		return
	}
	if in.ConversionUnit != "" && in.SettlementUnit == in.ConversionUnit {
		fail(w, 400, "UNIT_DUPLICATED", "计价单位与换算单位不能相同")
		return
	}
	if in.Conversion <= 0 {
		in.Conversion = 1
	}
	res, err := a.db.Exec(`INSERT INTO categories(grp,name,batch,settlement_unit,conversion_unit,conversion,status,created_at) VALUES(?,?,?,?,?,?,'active',?)`,
		in.Group, in.Name, in.Batch, in.SettlementUnit, in.ConversionUnit, in.Conversion, now())
	if err != nil {
		fail(w, 409, "CATEGORY_EXISTS", "该品类+型号+批号+计价单位+换算单位已存在")
		return
	}
	id, _ := res.LastInsertId()
	a.log(nil, "CATEGORY_CREATE", fmt.Sprint(in.Group, "/", in.Name, "/", in.Batch))
	ok(w, map[string]any{"id": id})
}

func (a *App) categoryItem(w http.ResponseWriter, r *http.Request, rest string) {
	parts := strings.Split(rest, "/")
	id, _ := strconv.ParseInt(parts[0], 10, 64)
	if id <= 0 {
		fail(w, 400, "ID", "id 非法")
		return
	}
	if len(parts) > 1 && parts[1] == "status" {
		v := r.URL.Query().Get("value")
		if v != "active" && v != "disabled" {
			fail(w, 400, "STATUS", "状态非法")
			return
		}
		_, _ = a.db.Exec(`UPDATE categories SET status=? WHERE id=?`, v, id)
		ok(w, map[string]any{"id": id, "status": v})
		return
	}
	switch r.Method {
	case "PUT":
		var in Category
		_ = json.NewDecoder(r.Body).Decode(&in)
		if in.Group == "" || in.Name == "" || in.Batch == "" || in.SettlementUnit == "" {
			fail(w, 400, "FIELD_REQUIRED", "品类、型号、批号、计价单位必须填写")
			return
		}
		if in.Conversion <= 0 {
			in.Conversion = 1
		}
		_, err := a.db.Exec(`UPDATE categories SET grp=?,name=?,batch=?,settlement_unit=?,conversion_unit=?,conversion=? WHERE id=?`,
			in.Group, in.Name, in.Batch, in.SettlementUnit, in.ConversionUnit, in.Conversion, id)
		if err != nil {
			fail(w, 409, "CATEGORY_EXISTS", "该五元组已存在")
			return
		}
		ok(w, map[string]any{"id": id})
	case "DELETE":
		_, _ = a.db.Exec(`UPDATE categories SET status='deleted' WHERE id=?`, id)
		ok(w, map[string]any{"deleted": true})
	}
}

func (a *App) available(cid, cat int64) []map[string]any {
	rows, _ := a.db.Query(`SELECT COALESCE(i.batch_tag,'') b,
		SUM(CASE WHEN o.type IN ('delivery','pickup') THEN CAST(i.settlement_quantity AS REAL) ELSE 0 END) -
		SUM(CASE WHEN o.type IN ('delivery_return','pickup_return') THEN CAST(i.settlement_quantity AS REAL) ELSE 0 END)
		FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.customer_id=? AND i.category_id=? GROUP BY b`, cid, cat)
	out := []map[string]any{}
	if rows == nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var b string
		var v float64
		_ = rows.Scan(&b, &v)
		if v > 0 {
			out = append(out, map[string]any{"batch_tag": b, "available": f2(v)})
		}
	}
	return out
}

func (a *App) priceFor(cid, cat int64, batch string) string {
	var p string
	_ = a.db.QueryRow(`SELECT unit_price FROM customer_prices WHERE customer_id=? AND category_id=? AND batch_tag=?`, cid, cat, batch).Scan(&p)
	return p
}

func (a *App) createOrder(w http.ResponseWriter, r *http.Request, acc int64) {
	var in struct {
		Phone, Name, Type, Remark string
		Items                     []struct {
			CategoryID int64   `json:"category_id"`
			BatchTag   string  `json:"batch_tag"`
			InputUnit  string  `json:"input_unit"`
			Quantity   string  `json:"input_quantity"`
			UnitPrice  string  `json:"unit_price"`
		} `json:"items"`
	}
	_ = json.NewDecoder(r.Body).Decode(&in)
	if in.Type == "" || in.Phone == "" || len(in.Items) == 0 {
		fail(w, 400, "FIELD_REQUIRED", "类型、电话、明细必填")
		return
	}
	phone := strings.NewReplacer(" ", "", "-", "").Replace(in.Phone)
	cid := a.customerID(phone)
	if cid == 0 {
		res, _ := a.db.Exec(`INSERT INTO customers(name,phone,created_at) VALUES(?,?,?)`, in.Name, phone, now())
		cid, _ = res.LastInsertId()
	}
	type line struct {
		cat      Category
		settleQ  float64
		price    float64
		lineTot  float64
		inputQty string
		inputU   string
		batch    string
	}
	lines := []line{}
	for _, it := range in.Items {
		var c Category
		if err := a.db.QueryRow(`SELECT id,grp,name,batch,settlement_unit,conversion_unit,conversion FROM categories WHERE id=? AND status='active'`, it.CategoryID).
			Scan(&c.ID, &c.Group, &c.Name, &c.Batch, &c.SettlementUnit, &c.ConversionUnit, &c.Conversion); err != nil {
			fail(w, 400, "CATEGORY_NOT_FOUND", "型号不存在或未启用")
			return
		}
		if it.BatchTag == "" {
			fail(w, 400, "BATCH_REQUIRED", "该型号必须填写批号")
			return
		}
		qty := bd(it.Quantity)
		if qty <= 0 {
			fail(w, 400, "QUANTITY", "数量必须大于 0")
			return
		}
		conv := 1.0
		if it.InputUnit == c.ConversionUnit {
			conv = c.Conversion
		}
		settle := qty * conv
		if strings.HasSuffix(in.Type, "_return") {
			avail := 0.0
			for _, v := range a.available(cid, c.ID) {
				if v["batch_tag"] == it.BatchTag {
					avail = bd(v["available"].(string))
				}
			}
			if settle > avail {
				fail(w, 400, "RETURN_EXCEEDS_AVAILABLE", "退货超出可退余量")
				return
			}
		}
		p := a.priceFor(cid, c.ID, it.BatchTag)
		if p == "" {
			if it.UnitPrice == "" {
				fail(w, 400, "PRICE_REQUIRED", "该批次无价格，请录入单价")
				return
			}
			p = f2(bd(it.UnitPrice))
			_, _ = a.db.Exec(`INSERT INTO customer_prices(customer_id,category_id,batch_tag,unit_price,updated_at) VALUES(?,?,?,?,?)
				ON CONFLICT(customer_id,category_id,batch_tag) DO UPDATE SET unit_price=excluded.unit_price`, cid, c.ID, it.BatchTag, p, now())
		} else if it.UnitPrice != "" && f2(bd(it.UnitPrice)) != p {
			fail(w, 400, "PRICE_EXISTS", "价格已存在，当前批次单价为 "+p)
			return
		}
		lines = append(lines, line{c, settle, bd(p), settle * bd(p), it.Quantity, it.InputUnit, it.BatchTag})
	}
	total := 0.0
	for _, l := range lines {
		total += l.lineTot
	}
	neg := strings.HasSuffix(in.Type, "_return")
	if neg {
		total = -total
	}
	prefix := map[string]string{"delivery": "D", "delivery_return": "R", "pickup": "P", "pickup_return": "R"}[in.Type]
	if prefix == "" {
		prefix = "D"
	}
	var seq int
	_ = a.db.QueryRow(`SELECT COUNT(*)+1 FROM orders WHERE order_no LIKE ?`, prefix+"%").Scan(&seq)
	no := fmt.Sprintf("%s%s%03d", prefix, time.Now().Format("20060102"), seq)
	res, err := a.db.Exec(`INSERT INTO orders(order_no,type,customer_id,account_id,status,remark,order_total,version,created_at) VALUES(?,?,?,?,'submitted',?,?,1,?)`,
		no, in.Type, cid, acc, in.Remark, f2(total), now())
	if err != nil {
		fail(w, 500, "DB", err.Error())
		return
	}
	oid, _ := res.LastInsertId()
	for i, l := range lines {
		_, _ = a.db.Exec(`INSERT INTO order_items(order_id,line_no,category_id,batch_tag,input_unit,input_quantity,settlement_unit,settlement_quantity,unit_price,line_total) VALUES(?,?,?,?,?,?,?,?,?,?)`,
			oid, i+1, l.cat.ID, l.batch, l.inputU, l.inputQty, l.cat.SettlementUnit, f2(l.settleQ), f2(l.price), f2(l.lineTot))
	}
	_, _ = a.db.Exec(`INSERT INTO print_jobs(order_id,doc_no,doc_type,status,created_at) VALUES(?,?,'order','pending',?)`, oid, no, now())
	a.log(&acc, "ORDER_CREATE", no)
	o := a.order(oid)
	ok(w, o)
}

func (a *App) updateOrder(w http.ResponseWriter, r *http.Request, acc int64, rawID string) {
	fail(w, 501, "TODO", "改单接口开发中")
}

func (a *App) order(oid int64) map[string]any {
	var o struct {
		ID                                                   int64
		No, Type, Status, Remark, Total, Created              string
		Version                                              int
		CName, CPhone, AName                                 string
	}
	err := a.db.QueryRow(`SELECT o.id,o.order_no,o.type,o.status,COALESCE(o.remark,''),o.order_total,o.created_at,o.version,cu.name,cu.phone,a.name
		FROM orders o JOIN customers cu ON cu.id=o.customer_id JOIN accounts a ON a.id=o.account_id WHERE o.id=?`, oid).
		Scan(&o.ID, &o.No, &o.Type, &o.Status, &o.Remark, &o.Total, &o.Created, &o.Version, &o.CName, &o.CPhone, &o.AName)
	if err != nil {
		return nil
	}
	items := []map[string]any{}
	rows, _ := a.db.Query(`SELECT i.category_id,c.grp,c.name,c.batch,i.batch_tag,i.input_unit,i.input_quantity,i.settlement_unit,i.settlement_quantity,i.unit_price,i.line_total
		FROM order_items i JOIN categories c ON c.id=i.category_id WHERE i.order_id=?`, oid)
	if rows != nil {
		defer rows.Close()
		for rows.Next() {
			var catID int64
			var grp, nm, bat, bt, iu, iq, su, sq, up, lt string
			_ = rows.Scan(&catID, &grp, &nm, &bat, &bt, &iu, &iq, &su, &sq, &up, &lt)
			items = append(items, map[string]any{"category_id": catID, "group": grp, "name": nm, "batch": bat, "batch_tag": bt,
				"input_unit": iu, "input_quantity": iq, "settlement_unit": su, "settlement_quantity": sq, "unit_price": up, "line_total": lt})
		}
	}
	var cum float64
	_ = a.db.QueryRow(`SELECT COALESCE(SUM(CAST(o2.order_total AS REAL)),0) FROM orders o2 JOIN customers c2 ON c2.id=o2.customer_id WHERE c2.phone=?`, o.CPhone).Scan(&cum)
	return map[string]any{"id": o.ID, "order_no": o.No, "type": o.Type, "status": o.Status, "remark": o.Remark,
		"order_total": o.Total, "version": o.Version, "created_at": o.Created, "customer_name": o.CName,
		"customer_phone": o.CPhone, "account_name": o.AName, "items": items, "cumulative_total": f2(cum)}
}

func (a *App) orders(where string, args []any) []map[string]any {
	rows, _ := a.db.Query(`SELECT o.id FROM orders o JOIN customers cu ON cu.id=o.customer_id WHERE `+where+` ORDER BY o.id DESC LIMIT 100`, args...)
	out := []map[string]any{}
	if rows == nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		_ = rows.Scan(&id)
		if o := a.order(id); o != nil {
			out = append(out, o)
		}
	}
	return out
}

func (a *App) createNote(w http.ResponseWriter, r *http.Request, acc int64) {
	var in struct {
		RefOrderNo string `json:"ref_order_no"`
		Items      []struct {
			CategoryID int64  `json:"category_id"`
			BatchTag   string `json:"batch_tag"`
			DiffType   string `json:"diff_type"`
			Quantity   string `json:"diff_quantity"`
			Remark     string `json:"remark"`
		} `json:"items"`
	}
	_ = json.NewDecoder(r.Body).Decode(&in)
	var refID, cid int64
	if err := a.db.QueryRow(`SELECT id,customer_id FROM orders WHERE order_no=?`, in.RefOrderNo).Scan(&refID, &cid); err != nil {
		fail(w, 400, "ORDER_NOT_FOUND", "原单不存在")
		return
	}
	var seq int
	_ = a.db.QueryRow(`SELECT COUNT(*)+1 FROM notes`).Scan(&seq)
	no := fmt.Sprintf("N%s%03d", time.Now().Format("20060102"), seq)
	res, _ := a.db.Exec(`INSERT INTO notes(note_no,ref_order_no,customer_id,account_id,status,created_at) VALUES(?,?,?,?,'active',?)`, no, in.RefOrderNo, cid, acc, now())
	nid, _ := res.LastInsertId()
	for _, it := range in.Items {
		_, _ = a.db.Exec(`INSERT INTO note_items(note_id,category_id,batch_tag,diff_type,diff_quantity,remark) VALUES(?,?,?,?,?,?)`,
			nid, it.CategoryID, it.BatchTag, it.DiffType, it.Quantity, it.Remark)
	}
	_, _ = a.db.Exec(`INSERT INTO print_jobs(doc_no,doc_type,status,created_at) VALUES(?,?,'pending',?)`, no, "note", now())
	a.log(&acc, "NOTE_CREATE", no)
	ok(w, a.note(nid))
}

func (a *App) note(id int64) map[string]any {
	var no, ref, status, created string
	if err := a.db.QueryRow(`SELECT note_no,ref_order_no,status,created_at FROM notes WHERE id=?`, id).Scan(&no, &ref, &status, &created); err != nil {
		return nil
	}
	items := []map[string]any{}
	rows, _ := a.db.Query(`SELECT i.category_id,c.name,i.batch_tag,i.diff_type,i.diff_quantity,COALESCE(i.remark,'') FROM note_items i JOIN categories c ON c.id=i.category_id WHERE i.note_id=?`, id)
	if rows != nil {
		defer rows.Close()
		for rows.Next() {
			var catID int64
			var nm, bt, dt, q, rm string
			_ = rows.Scan(&catID, &nm, &bt, &dt, &q, &rm)
			items = append(items, map[string]any{"category_id": catID, "category_name": nm, "batch_tag": bt, "diff_type": dt, "diff_quantity": q, "remark": rm})
		}
	}
	return map[string]any{"id": id, "note_no": no, "ref_order_no": ref, "status": status, "created_at": created, "items": items}
}

func (a *App) history(phone string) map[string]any {
	cid := a.customerID(phone)
	var name string
	_ = a.db.QueryRow(`SELECT name FROM customers WHERE id=?`, cid).Scan(&name)
	orders := a.orders(`cu.phone=?`, []any{phone})
	total := 0.0
	for _, o := range orders {
		total += bd(o["order_total"].(string))
	}
	return map[string]any{"customer": map[string]any{"id": cid, "name": name, "phone": phone}, "settlement_total": f2(total), "orders": orders}
}

func (a *App) printJobs() []map[string]any {
	rows, _ := a.db.Query(`SELECT id,COALESCE(doc_no,''),doc_type,status,created_at FROM print_jobs ORDER BY id DESC LIMIT 50`)
	out := []map[string]any{}
	if rows == nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var no, t, st, cr string
		_ = rows.Scan(&id, &no, &t, &st, &cr)
		out = append(out, map[string]any{"id": id, "doc_no": no, "doc_type": t, "status": st, "created_at": cr})
	}
	return out
}

func (a *App) settlement() []map[string]any {
	rows, _ := a.db.Query(`SELECT c.id,c.name,c.phone,COALESCE(SUM(CAST(o.order_total AS REAL)),0),COALESCE(MAX(o.created_at),'')
		FROM customers c LEFT JOIN orders o ON o.customer_id=c.id GROUP BY c.id ORDER BY 4 DESC`)
	out := []map[string]any{}
	if rows == nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var n, p, last string
		var total float64
		_ = rows.Scan(&id, &n, &p, &total, &last)
		out = append(out, map[string]any{"id": id, "name": n, "phone": p, "total": f2(total), "last": last})
	}
	return out
}

func (a *App) accounts() []map[string]any {
	rows, _ := a.db.Query(`SELECT id,username,name,role,status FROM accounts WHERE hidden=0 ORDER BY id`)
	out := []map[string]any{}
	if rows == nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var u, n, r, s string
		_ = rows.Scan(&id, &u, &n, &r, &s)
		out = append(out, map[string]any{"id": id, "username": u, "name": n, "role": r, "status": s})
	}
	return out
}

func (a *App) activeAdminCount() int {
	var n int
	_ = a.db.QueryRow(`SELECT COUNT(*) FROM accounts WHERE role='admin' AND status='active' AND hidden=0`).Scan(&n)
	return n
}

func (a *App) accountCreate(w http.ResponseWriter, r *http.Request) {
	var in struct{ Username, Name, Password, Role, Status string }
	_ = json.NewDecoder(r.Body).Decode(&in)
	if in.Username == "" || in.Name == "" || in.Password == "" {
		fail(w, 400, "FIELD_REQUIRED", "账号、姓名、密码必填")
		return
	}
	if in.Role == "" {
		in.Role = "staff"
	}
	if in.Status == "" {
		in.Status = "active"
	}
	_, err := a.db.Exec(`INSERT INTO accounts(username,name,password_hash,role,status,created_at) VALUES(?,?,?,?,?,?)`,
		in.Username, in.Name, hash(in.Password), in.Role, in.Status, now())
	if err != nil {
		fail(w, 409, "ACCOUNT_EXISTS", "账号已存在")
		return
	}
	a.log(nil, "ACCOUNT_CREATE", in.Username)
	ok(w, map[string]any{"created": true})
}

func (a *App) accountItem(w http.ResponseWriter, r *http.Request, rest string) {
	parts := strings.Split(rest, "/")
	id, _ := strconv.ParseInt(parts[0], 10, 64)
	if id <= 0 {
		fail(w, 400, "ID", "id 非法")
		return
	}
	var hidden int
	_ = a.db.QueryRow(`SELECT hidden FROM accounts WHERE id=?`, id).Scan(&hidden)
	if hidden == 1 {
		fail(w, 400, "DEV_ACCOUNT", "内置开发账号不可修改或删除")
		return
	}
	switch {
	case len(parts) > 1 && parts[1] == "status":
		v := r.URL.Query().Get("value")
		if v != "active" && v != "disabled" {
			fail(w, 400, "STATUS", "状态非法")
			return
		}
		var role, old string
		_ = a.db.QueryRow(`SELECT role,status FROM accounts WHERE id=?`, id).Scan(&role, &old)
		if role == "admin" && old == "active" && v == "disabled" && a.activeAdminCount() <= 1 {
			fail(w, 400, "LAST_ADMIN", "至少保留一个启用状态的管理员")
			return
		}
		_, _ = a.db.Exec(`UPDATE accounts SET status=? WHERE id=?`, v, id)
		ok(w, map[string]any{"id": id, "status": v})
	case len(parts) > 1 && parts[1] == "password":
		var in struct{ Password string }
		_ = json.NewDecoder(r.Body).Decode(&in)
		if len(in.Password) < 6 {
			fail(w, 400, "PASSWORD", "密码至少 6 位")
			return
		}
		_, _ = a.db.Exec(`UPDATE accounts SET password_hash=? WHERE id=?`, hash(in.Password), id)
		ok(w, map[string]any{"reset": true})
	case r.Method == "PUT":
		var in struct{ Name, Role, Status, Password string }
		_ = json.NewDecoder(r.Body).Decode(&in)
		var role, status string
		if err := a.db.QueryRow(`SELECT role,status FROM accounts WHERE id=?`, id).Scan(&role, &status); err != nil {
			fail(w, 404, "NOT_FOUND", "账号不存在")
			return
		}
		if in.Role == "" {
			in.Role = role
		}
		if in.Status == "" {
			in.Status = status
		}
		if role == "admin" && status == "active" && (in.Role != "admin" || in.Status != "active") && a.activeAdminCount() <= 1 {
			fail(w, 400, "LAST_ADMIN", "至少保留一个启用状态的管理员")
			return
		}
		_, _ = a.db.Exec(`UPDATE accounts SET name=?,role=?,status=? WHERE id=?`, in.Name, in.Role, in.Status, id)
		if in.Password != "" {
			_, _ = a.db.Exec(`UPDATE accounts SET password_hash=? WHERE id=?`, hash(in.Password), id)
		}
		ok(w, map[string]any{"id": id})
	case r.Method == "DELETE":
		var role, status string
		_ = a.db.QueryRow(`SELECT role,status FROM accounts WHERE id=?`, id).Scan(&role, &status)
		if role == "admin" && status == "active" && a.activeAdminCount() <= 1 {
			fail(w, 400, "LAST_ADMIN", "至少保留一个启用状态的管理员")
			return
		}
		_, _ = a.db.Exec(`DELETE FROM accounts WHERE id=?`, id)
		ok(w, map[string]any{"deleted": true})
	default:
		fail(w, 405, "METHOD", "不支持的方法")
	}
}

func (a *App) settings(w http.ResponseWriter, r *http.Request) {
	if r.Method == "GET" {
		rows, _ := a.db.Query(`SELECT k,v FROM settings`)
		m := map[string]string{}
		if rows != nil {
			defer rows.Close()
			for rows.Next() {
				var k, v string
				_ = rows.Scan(&k, &v)
				m[k] = v
			}
		}
		ok(w, m)
		return
	}
	var in map[string]string
	_ = json.NewDecoder(r.Body).Decode(&in)
	for k, v := range in {
		_, _ = a.db.Exec(`INSERT INTO settings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v`, k, v)
	}
	ok(w, map[string]any{"saved": true})
}

func (a *App) dashboard() map[string]any {
	var today, pending int
	_ = a.db.QueryRow(`SELECT COUNT(*) FROM orders WHERE date(created_at)=date('now','localtime')`).Scan(&today)
	_ = a.db.QueryRow(`SELECT COUNT(*) FROM print_jobs WHERE status='pending'`).Scan(&pending)
	return map[string]any{"today_orders": today, "pending_print": pending, "recent": a.orders(`1=1`, nil)}
}

func (a *App) log(acc *int64, action, detail string) {
	_, _ = a.db.Exec(`INSERT INTO logs(account_id,action,detail,created_at) VALUES(?,?,?,?)`, acc, action, detail, now())
}

var _ = big.NewInt
var _ = fmt.Sprint

func (a *App) settingInt(key string) int {
	var v string
	_ = a.db.QueryRow(`SELECT v FROM settings WHERE k=?`, key).Scan(&v)
	n, _ := strconv.Atoi(v)
	return n
}