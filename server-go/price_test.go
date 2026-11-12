package main

import (
	"database/sql"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

// 价格唯一性：客户+型号+批次；同键 upsert 只有一行，换批次可以是另一行。
func TestCustomerPriceUniquePerCustomerCategoryBatch(t *testing.T) {
	db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "p.sq3"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	migrate(db)

	up := func(batch, price string) {
		if _, err := db.Exec(`INSERT INTO customer_prices(customer_id,category_id,batch_tag,unit_price,updated_at) VALUES(1,2,?,?,'t')
			ON CONFLICT(customer_id,category_id,batch_tag) DO UPDATE SET unit_price=excluded.unit_price`, batch, price); err != nil {
			t.Fatalf("upsert %s: %v", batch, err)
		}
	}
	up("A12", "45.00")
	up("A12", "46.00")
	up("B05", "38.00")

	var n int
	_ = db.QueryRow(`SELECT COUNT(*) FROM customer_prices WHERE customer_id=1 AND category_id=2`).Scan(&n)
	if n != 2 {
		t.Fatalf("同客户同型号应为 2 个批次，实际 %d", n)
	}
	var price string
	_ = db.QueryRow(`SELECT unit_price FROM customer_prices WHERE customer_id=1 AND category_id=2 AND batch_tag='A12'`).Scan(&price)
	if price != "46.00" {
		t.Fatalf("同键应被 upsert 覆盖为 46.00，实际 %s", price)
	}
}
