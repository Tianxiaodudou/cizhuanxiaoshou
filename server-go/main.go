// czxs 服务端（Go）：单进程、单库 SQLite、双入口。
//
//   - Unix Socket：飞牛统一网关 /app/czxs → 静态管理页（React 构建产物，embed）
//   - TCP 端口：Android 客户端 JSON API（/api/*）
//
// 网关会透传 X-Trim-Userid / X-Trim-Isadmin / X-Trim-Username，前端用相对路径 fetch。
package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/fs"
	"log"
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

type server struct {
	db     *sql.DB
	prefix string
	mu     sync.Mutex
	tokens map[string]int64
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	home := env("TRIM_APPDEST", ".")
	data := env("TRIM_PKGVAR", filepath.Join(home, "data"))
	_ = os.MkdirAll(data, 0o755)
	socket := env("CZ_XS_SOCKET", filepath.Join(home, "app.sock"))
	port := 8080
	if p, err := strconv.Atoi(env("CZ_XS_API_PORT", "8080")); err == nil {
		port = p
	}
	prefix := strings.TrimRight(env("CZ_XS_PREFIX", "/app/czxs"), "/")

	db, err := sql.Open("sqlite", filepath.Join(data, "orders.sq3")+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)")
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	db.SetMaxOpenConns(1)
	migrate(db)
	seed(db)

	s := &server{db: db, prefix: prefix, tokens: map[string]int64{}}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/", s.api)
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"ok": true, "app": "czxs", "version": "2.0.0"})
	})
	mux.Handle("/admin/", s.spa())
	mux.HandleFunc("/admin", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, prefix+"/admin/", http.StatusFound)
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" || r.URL.Path == "" {
			http.Redirect(w, r, prefix+"/admin/", http.StatusFound)
			return
		}
		http.NotFound(w, r)
	})

	handler := withPrefix(prefix, mux)

	_ = os.Remove(socket)
	ln, err := net.Listen("unix", socket)
	if err != nil {
		log.Fatalf("listen unix %s: %v", socket, err)
	}
	go func() {
		log.Printf("gateway socket: %s (prefix %s)", socket, prefix)
		if err := http.Serve(ln, handler); err != nil {
			log.Printf("unix serve stopped: %v", err)
		}
	}()
	go func() {
		log.Printf("client api: 0.0.0.0:%d", port)
		if err := http.ListenAndServe(fmt.Sprintf(":%d", port), handler); err != nil {
			log.Printf("tcp serve stopped: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	_ = os.Remove(socket)
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
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE NOT NULL,
			name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'admin', status TEXT NOT NULL DEFAULT 'active')`,
		`CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS categories(id INTEGER PRIMARY KEY AUTOINCREMENT,
			grp TEXT NOT NULL, name TEXT NOT NULL, batch TEXT NOT NULL,
			settlement_unit TEXT NOT NULL, conversion_unit TEXT NOT NULL, conversion REAL NOT NULL DEFAULT 1,
			status TEXT NOT NULL DEFAULT 'active', created_at TEXT NOT NULL,
			UNIQUE(grp, name, batch, settlement_unit, conversion_unit))`,
		`CREATE TABLE IF NOT EXISTS customers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT UNIQUE NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS customer_prices(id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER NOT NULL,
			category_id INTEGER NOT NULL, unit_price TEXT NOT NULL, updated_at TEXT NOT NULL,
			UNIQUE(customer_id, category_id))`,
		`CREATE TABLE IF NOT EXISTS orders(id INTEGER PRIMARY KEY AUTOINCREMENT, order_no TEXT UNIQUE NOT NULL,
			type TEXT NOT NULL, customer_id INTEGER NOT NULL, account_id INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'submitted',
			order_total TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS order_items(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL,
			category_id INTEGER NOT NULL, batch_tag TEXT, input_unit TEXT NOT NULL, input_quantity TEXT NOT NULL,
			settlement_unit TEXT NOT NULL, settlement_quantity TEXT NOT NULL, unit_price TEXT NOT NULL, line_total TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS operation_logs(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER,
			action TEXT NOT NULL, detail TEXT, created_at TEXT NOT NULL)`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			log.Fatalf("migrate: %v", err)
		}
	}
}

func seed(db *sql.DB) {
	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM accounts`).Scan(&n)
	if n == 0 {
		pw := env("CZ_XS_ADMIN_PASSWORD", "admin123")
		_, _ = db.Exec(`INSERT INTO accounts(username,name,password_hash,role) VALUES(?,?,?,'admin')`,
			"admin", "管理员", hashPassword(pw))
		log.Printf("已创建初始管理员 admin（密码来自 CZ_XS_ADMIN_PASSWORD 或默认 admin123）")
	}
	var m int
	_ = db.QueryRow(`SELECT COUNT(*) FROM settings`).Scan(&m)
	if m == 0 {
		_, _ = db.Exec(`INSERT INTO settings(key,value) VALUES('merchant_name','瓷砖店'),('initialized','true')`)
	}
}

func hashPassword(pw string) string {
	sum := sha256.Sum256([]byte("czxs$" + pw))
	return hex.EncodeToString(sum[:])
}

// ---------- HTTP 基础设施 ----------
func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func ok(w http.ResponseWriter, data any) { writeJSON(w, 200, map[string]any{"ok": true, "data": data}) }
func fail(w http.ResponseWriter, code int, c, msg string) {
	writeJSON(w, code, map[string]any{"ok": false, "code": c, "message": msg})
}

func (s *server) account(r *http.Request) (int64, bool) {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		return 0, false
	}
	t := strings.TrimPrefix(h, "Bearer ")
	s.mu.Lock()
	defer s.mu.Unlock()
	id, ok := s.tokens[t]
	return id, ok
}

// ---------- 静态资源（React build） ----------
func (s *server) spa() http.Handler {
	sub, err := fs.Sub(webFiles, "web/dist")
	if err != nil {
		log.Fatalf("embed web: %v", err)
	}
	files := http.FileServer(http.FS(sub))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(r.URL.Path, "/admin/")
		if p == "" || p == "/" {
			p = "index.html"
		}
		if _, err := fs.Stat(sub, p); err != nil {
			r.URL.Path = "/"
		} else {
			r.URL.Path = "/" + p
		}
		files.ServeHTTP(w, r)
	})
}

// ---------- API ----------
func (s *server) api(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api")
	switch {
	case path == "/auth/login" && r.Method == http.MethodPost:
		s.login(w, r)
	case path == "/me" && r.Method == http.MethodGet:
		id, authed := s.account(r)
		if !authed {
			fail(w, 401, "UNAUTHORIZED", "未登录")
			return
		}
		var name, role string
		_ = s.db.QueryRow(`SELECT name, role FROM accounts WHERE id=?`, id).Scan(&name, &role)
		ok(w, map[string]any{"id": id, "name": name, "role": role})
	case path == "/categories":
		s.categories(w, r)
	case strings.HasPrefix(path, "/categories/"):
		s.categoryItem(w, r, strings.TrimPrefix(path, "/categories/"))
	case path == "/gateway/whoami":
		ok(w, map[string]any{
			"userid":  r.Header.Get("X-Trim-Userid"),
			"isadmin": r.Header.Get("X-Trim-Isadmin"),
			"name":    r.Header.Get("X-Trim-Username"),
		})
	default:
		fail(w, 404, "NOT_FOUND", "接口不存在")
	}
}


func (s *server) categoryItem(w http.ResponseWriter, r *http.Request, rest string) {
	if _, authed := s.account(r); !authed {
		fail(w, 401, "UNAUTHORIZED", "未登录")
		return
	}
	parts := strings.Split(rest, "/")
	id, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || id <= 0 {
		fail(w, 400, "ID", "id 非法")
		return
	}
	if len(parts) == 2 && parts[1] == "status" {
		v := r.URL.Query().Get("value")
		if v != "active" && v != "disabled" {
			fail(w, 400, "STATUS", "状态非法")
			return
		}
		_, _ = s.db.Exec(`UPDATE categories SET status=? WHERE id=?`, v, id)
		ok(w, map[string]any{"id": id, "status": v})
		return
	}
	switch r.Method {
	case http.MethodPut:
		var in struct {
			Group, Name, Batch, SettlementUnit, ConversionUnit string `json:"-"`
			Conversion                                          float64 `json:"conversion"`
		}
		var raw map[string]any
		if e := json.NewDecoder(r.Body).Decode(&raw); e != nil {
			fail(w, 400, "BAD_JSON", "请求体非法")
			return
		}
		get := func(k string) string { v, _ := raw[k].(string); return v }
		in.Group, in.Name, in.Batch = get("group"), get("name"), get("batch")
		in.SettlementUnit, in.ConversionUnit = get("settlement_unit"), get("conversion_unit")
		if v, ok := raw["conversion"].(float64); ok {
			in.Conversion = v
		}
		if in.Group == "" || in.Name == "" || in.Batch == "" || in.SettlementUnit == "" || in.ConversionUnit == "" {
			fail(w, 400, "FIELD_REQUIRED", "五个字段都必须填写")
			return
		}
		if in.Conversion <= 0 {
			in.Conversion = 1
		}
		_, err := s.db.Exec(`UPDATE categories SET grp=?,name=?,batch=?,settlement_unit=?,conversion_unit=?,conversion=? WHERE id=?`,
			in.Group, in.Name, in.Batch, in.SettlementUnit, in.ConversionUnit, in.Conversion, id)
		if err != nil {
			fail(w, 409, "CATEGORY_EXISTS", "该五元组已存在")
			return
		}
		ok(w, map[string]any{"id": id})
	case http.MethodDelete:
		_, _ = s.db.Exec(`UPDATE categories SET status='deleted' WHERE id=?`, id)
		ok(w, map[string]any{"id": id, "deleted": true})
	default:
		fail(w, 405, "METHOD", "不支持的方法")
	}
}
func (s *server) login(w http.ResponseWriter, r *http.Request) {
	var in struct{ Username, Password string }
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		fail(w, 400, "BAD_JSON", "请求体非法")
		return
	}
	var id int64
	var name, hash string
	err := s.db.QueryRow(`SELECT id, name, password_hash FROM accounts WHERE username=? AND status='active'`, in.Username).
		Scan(&id, &name, &hash)
	if err != nil || hash != hashPassword(in.Password) {
		fail(w, 401, "AUTH_FAILED", "账号或密码错误")
		return
	}
	buf := make([]byte, 24)
	_, _ = rand.Read(buf)
	token := hex.EncodeToString(buf)
	s.mu.Lock()
	s.tokens[token] = id
	s.mu.Unlock()
	ok(w, map[string]any{"token": token, "account": map[string]any{"id": id, "username": in.Username, "name": name, "role": "admin"}})
}

type category struct {
	ID             int64   `json:"id"`
	Group          string  `json:"group"`
	Name           string  `json:"name"`
	Batch          string  `json:"batch"`
	SettlementUnit string  `json:"settlement_unit"`
	ConversionUnit string  `json:"conversion_unit"`
	Conversion     float64 `json:"conversion"`
	Status         string  `json:"status"`
}

func (s *server) categories(w http.ResponseWriter, r *http.Request) {
	if _, authed := s.account(r); !authed && r.Method != http.MethodGet {
		fail(w, 401, "UNAUTHORIZED", "未登录")
		return
	}
	switch r.Method {
	case http.MethodGet:
		rows, err := s.db.Query(`SELECT id,grp,name,batch,settlement_unit,conversion_unit,conversion,status
			FROM categories WHERE status != 'deleted' ORDER BY id DESC`)
		if err != nil {
			fail(w, 500, "DB", err.Error())
			return
		}
		defer rows.Close()
		list := []category{}
		for rows.Next() {
			var c category
			_ = rows.Scan(&c.ID, &c.Group, &c.Name, &c.Batch, &c.SettlementUnit, &c.ConversionUnit, &c.Conversion, &c.Status)
			list = append(list, c)
		}
		ok(w, list)
	case http.MethodPost:
		var in struct {
			Group, Name, Batch, SettlementUnit, ConversionUnit string
			Conversion                                          float64 `json:"conversion"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			fail(w, 400, "BAD_JSON", "请求体非法")
			return
		}
		if in.Group == "" || in.Name == "" || in.Batch == "" || in.SettlementUnit == "" || in.ConversionUnit == "" {
			fail(w, 400, "FIELD_REQUIRED", "五个字段都必须填写")
			return
		}
		if in.Conversion <= 0 {
			in.Conversion = 1
		}
		res, err := s.db.Exec(`INSERT INTO categories(grp,name,batch,settlement_unit,conversion_unit,conversion,status,created_at)
			VALUES(?,?,?,?,?,?, 'active', ?)`, in.Group, in.Name, in.Batch, in.SettlementUnit, in.ConversionUnit, in.Conversion, time.Now().Format(time.RFC3339))
		if err != nil {
			fail(w, 409, "CATEGORY_EXISTS", "该五元组已存在")
			return
		}
		id, _ := res.LastInsertId()
		_, _ = s.db.Exec(`INSERT INTO operation_logs(account_id,action,detail,created_at) VALUES(?,?,?,?)`, nil, "CATEGORY_CREATE", in.Group+"/"+in.Name+"/"+in.Batch, time.Now().Format(time.RFC3339))
		ok(w, map[string]any{"id": id})
	default:
		fail(w, 405, "METHOD", "不支持的方法")
	}
}
