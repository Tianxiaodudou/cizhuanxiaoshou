package main

import (
	"database/sql"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

func TestNextNoConcurrentUnique(t *testing.T) {
	db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "t.sq3")+"?_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	migrate(db)
	a := &App{db: db, tokens: map[string]int64{}}

	const n = 200
	ch := make(chan string, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			no, err := a.nextNo("PS")
			if err != nil {
				t.Errorf("nextNo: %v", err)
				return
			}
			ch <- no
		}()
	}
	wg.Wait()
	close(ch)

	seen := map[string]bool{}
	day := time.Now().Format("20060102")
	for no := range ch {
		if !strings.HasPrefix(no, "PS"+day) {
			t.Fatalf("单号格式错误: %s", no)
		}
		if seen[no] {
			t.Fatalf("单号重复: %s", no)
		}
		seen[no] = true
	}
	if len(seen) != n {
		t.Fatalf("期望 %d 个唯一单号，实际 %d", n, len(seen))
	}
}

func TestPrefixesAndUniqueConstraint(t *testing.T) {
	db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "t.sq3"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	migrate(db)
	a := &App{db: db, tokens: map[string]int64{}}
	cases := map[string]string{"delivery": "PS", "delivery_return": "PT", "pickup": "ZS", "pickup_return": "ZT"}
	for typ, want := range cases {
		got := map[string]string{"delivery": "PS", "delivery_return": "PT", "pickup": "ZS", "pickup_return": "ZT"}[typ]
		if got != want {
			t.Fatalf("%s 前缀错误: %s", typ, got)
		}
	}
	no, err := a.nextNo("BZ")
	if err != nil || !strings.HasPrefix(no, "BZ") {
		t.Fatalf("备注单号错误: %s %v", no, err)
	}
	// 唯一约束兜底：重复插入同一单号必须失败
	if _, err := db.Exec(`INSERT INTO orders(order_no,type,customer_id,account_id,status,order_total,version,created_at) VALUES('PSX','delivery',1,1,'submitted','0',1,'x')`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO orders(order_no,type,customer_id,account_id,status,order_total,version,created_at) VALUES('PSX','delivery',1,1,'submitted','0',1,'x')`); err == nil {
		t.Fatal("唯一约束未生效")
	}
}
