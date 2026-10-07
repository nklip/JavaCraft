#!/usr/bin/env bash
# Read-only verification of the migrated catalog and effective grants on every
# kind-shardshop instance. Run after migrate.sh; never applies SQL migrations.
set -euo pipefail
[[ $# == 0 ]] || { echo 'Usage: verify-catalog-lab.sh' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/shards.sh"
load_inventory
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cat >"$work/check.sql" <<'SQL'
BEGIN READ ONLY;
SELECT name || '=' || coalesce(passed, false)::text FROM (VALUES
    ('V2 applied', EXISTS (SELECT FROM catalog.flyway_schema_history WHERE version = '2' AND success)),
    ('history successful', NOT EXISTS (SELECT FROM catalog.flyway_schema_history WHERE NOT success)),
    ('catalog owners', NOT EXISTS (SELECT FROM pg_class WHERE relnamespace = 'catalog'::regnamespace
        AND relowner <> 'catalog_owner'::regrole)),
    ('company names', NOT EXISTS (SELECT FROM catalog.sellers WHERE company_name IS DISTINCT FROM name)),
    ('product descriptions', NOT EXISTS (SELECT FROM catalog.products WHERE description IS NULL)),
    ('initial currencies', NOT EXISTS (SELECT FROM catalog.sellers s
        CROSS JOIN (VALUES ('USD'), ('EUR')) AS c(currency)
        WHERE NOT EXISTS (SELECT FROM catalog.seller_profits p
            WHERE p.seller_id = s.seller_id AND p.currency = c.currency))),
    ('product reads profits', has_table_privilege('product_app', 'catalog.seller_profits', 'SELECT')),
    ('product initializes currency', has_column_privilege('product_app', 'catalog.seller_profits', 'currency', 'INSERT')),
    ('product cannot set amount', NOT has_column_privilege('product_app', 'catalog.seller_profits', 'amount', 'INSERT')),
    ('product cannot update profits', NOT has_any_column_privilege('product_app', 'catalog.seller_profits', 'UPDATE')),
    ('order reads profits', has_table_privilege('order_app', 'catalog.seller_profits', 'SELECT')),
    ('order cannot initialize profits', NOT has_any_column_privilege('order_app', 'catalog.seller_profits', 'INSERT')),
    ('order cannot update profits', NOT has_any_column_privilege('order_app', 'catalog.seller_profits', 'UPDATE')),
    ('order cannot delete profits', NOT has_table_privilege('order_app', 'catalog.seller_profits', 'DELETE')),
    ('order cannot truncate profits', NOT has_table_privilege('order_app', 'catalog.seller_profits', 'TRUNCATE')),
    ('order cannot read credits', NOT has_any_column_privilege('order_app', 'catalog.profit_credits', 'SELECT')),
    ('order cannot insert credits', NOT has_any_column_privilege('order_app', 'catalog.profit_credits', 'INSERT')),
    ('order cannot update credits', NOT has_any_column_privilege('order_app', 'catalog.profit_credits', 'UPDATE')),
    ('order cannot delete credits', NOT has_table_privilege('order_app', 'catalog.profit_credits', 'DELETE')),
    ('order cannot truncate credits', NOT has_table_privilege('order_app', 'catalog.profit_credits', 'TRUNCATE')),
    ('order can update stock', has_column_privilege('order_app', 'catalog.products', 'stock', 'UPDATE')),
    ('order cannot update prices', NOT has_column_privilege('order_app', 'catalog.products', 'price', 'UPDATE')),
    ('order reservation access', has_table_privilege('order_app', 'catalog.stock_reservations', 'SELECT')
        AND has_table_privilege('order_app', 'catalog.stock_reservations', 'INSERT')
        AND has_table_privilege('order_app', 'catalog.stock_reservations', 'UPDATE')
        AND has_table_privilege('order_app', 'catalog.stock_reservations', 'DELETE'))
) AS checks(name, passed);
ROLLBACK;
SQL
for shard in "${shards[@]}"; do
    selector="cnpg.io/cluster=$shard,cnpg.io/podRole=instance"
    k wait --for=condition=Ready pods -l "$selector" --timeout=180s
    pods=$(k get pods -l "$selector" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
    count=0
    while IFS= read -r pod; do
        [[ -n "$pod" ]] || continue
        count=$((count + 1))
        # Synchronous commit does not require every standby to have replayed DDL.
        deadline=$((SECONDS + 60))
        while :; do
            if k exec -i "$pod" -c postgres -- env \
                PGOPTIONS='-c statement_timeout=15000 -c lock_timeout=5000' \
                psql -X -qAt -v ON_ERROR_STOP=1 -U postgres -d shardshop \
                <"$work/check.sql" >"$work/result" 2>"$work/error" \
                && [[ ! -s "$work/error" ]] \
                && python3 - "$work/result" <<'PY'
import pathlib, sys
rows = pathlib.Path(sys.argv[1]).read_text().splitlines()
if len(rows) != 23 or any(not row.endswith("=true") for row in rows):
    sys.exit(1)
PY
            then
                echo "Verified $pod: 23 catalog V2, ownership, backfill and effective privilege checks."
                break
            fi
            if (( SECONDS >= deadline )); then
                cat "$work/error" "$work/result" >&2
                echo "Catalog verification failed on $pod" >&2
                exit 1
            fi
            sleep 1
        done
    done <<<"$pods"
    [[ "$count" == "$instances" ]] || { echo "$shard has $count instances, expected $instances" >&2; exit 1; }
done
