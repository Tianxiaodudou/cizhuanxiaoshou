#!/system/bin/sh
# 真机端到端联调脚本（在手机 shell 里跑，用 curl 打真实网关 S13）。
# 用法：adb shell sh /data/local/tmp/device-e2e.sh http://<PC-IP>:8080
# 只做只读 + 建单链路，不修改任何源码。
set -u

B="${1:-http://192.168.1.55:8080}"
J='Content-Type: application/json'
say() { echo; echo "=== $1"; }
json_id() { echo "$1" | sed -n 's/.*"id":\([0-9]*\).*/\1/p'; }
json_str() { echo "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }

say contract
curl -s -m 5 "$B/contract"
echo

say login
LOGIN=$(curl -s -m 5 -X POST "$B/auth/login" -H "$J" -d '{"username":"admin","password":"secret123"}')
echo "$LOGIN"
TOKEN=$(json_str "$LOGIN" token)
A="Authorization: Bearer $TOKEN"

say me
curl -s -m 5 -H "$A" "$B/me"
echo

say create_category
CAT=$(curl -s -m 5 -X POST "$B/categories" -H "$A" -H "$J" \
  -d '{"category_name":"Cement","model":"P.O42.5","unit":"bag","batch_tag_required":false}')
echo "$CAT"
CATID=$(json_id "$CAT")

say create_customer
CUS=$(curl -s -m 5 -X POST "$B/customers" -H "$A" -H "$J" \
  -d '{"name":"Zhang","phone":"13800000001"}')
echo "$CUS"
CUSID=$(json_id "$CUS")

say change_price
curl -s -m 5 -X POST "$B/prices/change" -H "$A" -H "$J" \
  -d "{\"customer_id\":$CUSID,\"category_id\":$CATID,\"batch_tag\":\"\",\"new_price\":\"100.00\",\"reason\":\"e2e\"}"
echo

say create_order
ORD=$(curl -s -m 5 -X POST "$B/orders" -H "$A" -H "$J" -H 'Idempotency-Key: dev-1' \
  -d "{\"client_request_id\":\"dev-1\",\"order_type\":\"purchase\",\"customer_id\":$CUSID,\"lines\":[{\"category_id\":$CATID,\"batch_tag\":\"\",\"unit\":\"bag\",\"quantity\":\"2\"}]}")
echo "$ORD"
ORNO=$(json_str "$ORD" order_no)

say replay_same_idempotency_key
curl -s -m 5 -D - -X POST "$B/orders" -H "$A" -H "$J" -H 'Idempotency-Key: dev-1' \
  -d "{\"client_request_id\":\"dev-1\",\"order_type\":\"purchase\",\"customer_id\":$CUSID,\"lines\":[{\"category_id\":$CATID,\"batch_tag\":\"\",\"unit\":\"bag\",\"quantity\":\"2\"}]}" \
  -o /dev/null | grep -i -E 'HTTP/|X-Idempotent-Replay|X-Trace-Id'

say order_create_after_replay_should_still_be_one
curl -s -m 5 -H "$A" "$B/orders/today" | sed -n 's/.*"total":\([0-9]*\).*/today_total=\1/p'

say today
curl -s -m 5 -H "$A" "$B/orders/today"
echo

say receivable
curl -s -m 5 -H "$A" "$B/orders/$ORNO/receivable"
echo

say submit_settlement
SET=$(curl -s -m 5 -X POST "$B/settlements" -H "$A" -H "$J" -H 'Idempotency-Key: dev-s1' \
  -d "{\"client_request_id\":\"dev-s1\",\"order_no\":\"$ORNO\",\"direction\":\"receipt\",\"amount\":\"50.00\"}")
echo "$SET"
SETNO=$(json_str "$SET" settle_no)

say audit_settlement
curl -s -m 5 -X POST "$B/settlements/$SETNO/audit" -H "$A" -H "$J" \
  -d "{\"settle_no\":\"$SETNO\",\"approved\":true}"
echo

say balance
curl -s -m 5 -H "$A" "$B/customers/$CUSID/balance"
echo

say create_note
curl -s -m 5 -X POST "$B/notes" -H "$A" -H "$J" -H 'Idempotency-Key: dev-n1' \
  -d "{\"source_order_no\":\"$ORNO\",\"diff_type\":\"price\",\"diff_amount\":\"5.00\",\"remark\":\"e2e\"}"
echo

say audit_trail
curl -s -m 5 -H "$A" "$B/audit"
echo

say print_ticket
curl -s -m 5 -H "$A" "$B/print/$ORNO" -o /tmp/ticket.txt -D - | grep -i -E 'HTTP/|X-Print-Count|X-Paper-Width-Mm|Content-Type'
echo '--- ticket 80mm ---'
cat /tmp/ticket.txt

say permission_without_token
curl -s -m 5 -o /dev/null -w 'status=%{http_code}\n' "$B/orders/today"

say done
