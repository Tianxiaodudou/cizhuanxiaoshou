package main

import (
	"database/sql"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

// 验证旧 orders.sq3 拆分为 docs/model/accounts 三库，且数据不丢。
func TestLegacySplitMigration(t *testing.T) {
	dir := t.TempDir()
	legacy := filepath.Join(dir, "orders.sq3")
	old, err := sql.Open("sqlite", legacy)
	if err != nil {
		t.Fatal(err)
	}
	stmts := []string{
		`CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE, name TEXT, password_hash TEXT, role TEXT, status TEXT, created_at TEXT)`,
		`CREATE TABLE settings(k TEXT PRIMARY KEY, v TEXT)`,
		`CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT, grp TEXT, name TEXT, batch TEXT, settlement_unit TEXT, conversion_unit TEXT, conversion REAL, default_price TEXT, batch_type TEXT, status TEXT, created_at TEXT)`,
		`CREATE TABLE customers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, phone TEXT UNIQUE, addr TEXT, price_mode TEXT, created_at TEXT)`,
		`CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT, order_no TEXT, type TEXT, customer_id INTEGER, account_id INTEGER, status TEXT, remark TEXT, order_total TEXT, version INTEGER, created_at TEXT, discount TEXT, discount_type TEXT)`,
		`CREATE TABLE order_items(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, line_no INTEGER, category_id INTEGER, batch_tag TEXT, input_unit TEXT, input_quantity TEXT, settlement_unit TEXT, settlement_quantity TEXT, unit_price TEXT, line_total TEXT)`,
		`CREATE TABLE payment_lines(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, method TEXT, custom_method TEXT, amount TEXT)`,
		`CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, note_no TEXT, ref_order_no TEXT, customer_id INTEGER, account_id INTEGER, status TEXT, remark TEXT, created_at TEXT)`,
		`CREATE TABLE note_items(id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER, category_id INTEGER, batch_tag TEXT, diff_type TEXT, diff_quantity TEXT, remark TEXT)`,
		`CREATE TABLE print_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, doc_no TEXT, doc_type TEXT, status TEXT, created_at TEXT)`,
		`CREATE TABLE doc_seq(prefix TEXT, day TEXT, seq INTEGER, PRIMARY KEY(prefix,day))`,
		`CREATE TABLE logs(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER, action TEXT, detail TEXT, created_at TEXT)`,
		`INSERT INTO accounts(username,name,password_hash,role,status,created_at) VALUES('boss','老板','h','admin','active','t')`,
		`INSERT INTO categories(grp,name,batch,settlement_unit,conversion_unit,conversion,default_price,batch_type,status,created_at) VALUES('瓷砖','800x800','A1','片','箱',6,'45','none','active','t')`,
		`INSERT INTO customers(name,phone,addr,price_mode,created_at) VALUES('张三','13800000000','马庄','open','t')`,
		`INSERT INTO orders(order_no,type,customer_id,account_id,status,remark,order_total,version,created_at,discount,discount_type) VALUES('PS202601011','delivery',1,1,'submitted','','900',1,'2026-01-01T10:00:00+08:00','0','')`,
		`INSERT INTO order_items(order_id,line_no,category_id,batch_tag,input_unit,input_quantity,settlement_unit,settlement_quantity,unit_price,line_total) VALUES(1,1,1,'A1','片','20','片','20','45','900')`,
		`INSERT INTO settings(k,v) VALUES('settlement_units','片,箱')`,
		`INSERT INTO settings(k,v) VALUES('merchant_name','测试店')`,
	}
	for _, s := range stmts {
		if _, err := old.Exec(s); err != nil {
			t.Fatalf("%v: %s", err, s)
		}
	}
	_ = old.Close()

	docs, _ := sql.Open("sqlite", filepath.Join(dir, "docs.sq3")+"?_pragma=busy_timeout(5000)")
	defer docs.Close()
	model, _ := sql.Open("sqlite", filepath.Join(dir, "model.sq3")+"?_pragma=busy_timeout(5000)")
	defer model.Close()
	acc, _ := sql.Open("sqlite", filepath.Join(dir, "accounts.sq3")+"?_pragma=busy_timeout(5000)")
	defer acc.Close()
	migrate(docs)
	migrateModel(model)
	migrateAccounts(acc)
	migrateLegacy(docs, model, acc, dir)
	backfillItemNames(docs, model)

	var n int
	_ = model.QueryRow(`SELECT COUNT(*) FROM categories`).Scan(&n)
	if n != 1 {
		t.Fatalf("型号库 categories 期望 1，实际 %d", n)
	}
	var units string
	_ = model.QueryRow(`SELECT v FROM settings WHERE k='settlement_units'`).Scan(&units)
	if units != "片,箱" {
		t.Fatalf("型号库预设未迁移：%q", units)
	}
	_ = docs.QueryRow(`SELECT COUNT(*) FROM customers`).Scan(&n)
	if n != 1 {
		t.Fatalf("单据库 customers 期望 1，实际 %d", n)
	}
	_ = docs.QueryRow(`SELECT COUNT(*) FROM orders`).Scan(&n)
	if n != 1 {
		t.Fatalf("单据库 orders 期望 1，实际 %d", n)
	}
	var cname, cgrp string
	_ = docs.QueryRow(`SELECT cat_name, cat_group FROM order_items WHERE id=1`).Scan(&cname, &cgrp)
	if cname != "800x800" || cgrp != "瓷砖" {
		t.Fatalf("明细名称快照未回填：%q %q", cname, cgrp)
	}
	var uname string
	_ = acc.QueryRow(`SELECT username FROM accounts LIMIT 1`).Scan(&uname)
	if uname != "boss" {
		t.Fatalf("账号未迁移：%q", uname)
	}
	// 单据库不应再含账号表
	var hasAcc int
	_ = docs.QueryRow(`SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='accounts'`).Scan(&hasAcc)
	if hasAcc != 0 {
		t.Fatalf("单据库不应包含 accounts 表")
	}
}
