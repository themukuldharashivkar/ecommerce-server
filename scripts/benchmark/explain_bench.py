"""Run each query N times with EXPLAIN (ANALYZE, BUFFERS) and report median execution time + plan shape.
SQL is exactly what Hibernate generates (captured from show-sql), with the parameters bound as literals.
Usage: python3 -I explain_bench.py <label>
Env: BENCH_CONTAINER (default bazaarly-postgres), BENCH_DB (default ecommerce_bench), BENCH_USER (default bazaarly)."""
import json, os, statistics, subprocess, sys

CONTAINER = os.environ.get("BENCH_CONTAINER", "bazaarly-postgres")
DB = os.environ.get("BENCH_DB", "ecommerce_bench")
USER = os.environ.get("BENCH_USER", "bazaarly")

RUNS = 9
COLS = ("p1_0.id,p1_0.brand,p1_0.category_id,p1_0.color,p1_0.created_at,p1_0.description,"
        "p1_0.discount_persent,p1_0.discounted_price,p1_0.image_url,p1_0.num_ratings,p1_0.price,"
        "p1_0.quantity,p1_0.title")

def catalog(category, min_price, max_price, min_discount, sort):
    return (f"select {COLS} from product p1_0 join categories c1_0 on c1_0.id=p1_0.category_id "
            f"where (lower(c1_0.name)=lower('{category}') or '{category}'='') "
            f"and (({min_price} is null and {max_price} is null) or (p1_0.discounted_price between {min_price} and {max_price})) "
            f"and ({min_discount} is null or p1_0.discount_persent>={min_discount}) "
            f"order by case when '{sort}'='price_low' then p1_0.discounted_price end asc,"
            f"case when '{sort}'='price_high' then p1_0.discounted_price end desc,p1_0.id asc")

def search(term):
    t = f"'%{term}%'"
    return (f"select {COLS} from product p1_0 join categories c1_0 on c1_0.id=p1_0.category_id "
            f"where lower(p1_0.title) like {t} escape '' or lower(p1_0.description) like {t} escape '' "
            f"or lower(p1_0.brand) like {t} escape '' or lower(c1_0.name) like {t} escape ''")

def category_ids(term):
    out = subprocess.run(["docker", "exec", CONTAINER, "psql", "-U", USER, "-d", DB, "-At", "-c",
                          f"select coalesce(string_agg(id::text, ','), '-1') from categories where lower(name) like '%{term}%'"],
                         capture_output=True, text=True, check=True).stdout.strip()
    return out

def search_new(term):
    t = f"'%{term}%'"
    return (f"select {COLS} from product p1_0 where lower(p1_0.title) like {t} escape '' "
            f"or lower(p1_0.description) like {t} escape '' or lower(p1_0.brand) like {t} escape '' "
            f"or p1_0.category_id in({category_ids(term)})")

QUERIES = {
    "catalog: men_jeans, any price, sort price_low": catalog("men_jeans", 0, 100000, 0, "price_low"),
    "catalog: men_jeans, price 500-1000, sort price_low": catalog("men_jeans", 500, 1000, 0, "price_low"),
    "catalog: saree, price 500-1000, min discount 50%": catalog("saree", 500, 1000, 50, "price_low"),
    "search (old query, join): 'jeans'": search("jeans"),
    "search (old query, join): 'silk'": search("silk"),
    "search (new query, category ids): 'jeans'": search_new("jeans"),
    "search (new query, category ids): 'silk'": search_new("silk"),
}

def explain(sql):
    out = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", USER, "-d", DB, "-At"],
        input=f"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) {sql};", capture_output=True, text=True, check=True).stdout
    return json.loads(out)[0]

def nodes(plan, acc):
    name = plan["Node Type"] + (f" on {plan['Relation Name']}" if "Relation Name" in plan else "")
    if "Index Name" in plan:
        name += f" using {plan['Index Name']}"
    acc.append(name)
    for child in plan.get("Plans", []):
        nodes(child, acc)
    return acc

label = sys.argv[1]
result = {}
for name, sql in QUERIES.items():
    explain(sql)  # warm-up
    runs = [explain(sql) for _ in range(RUNS)]
    times = [r["Execution Time"] for r in runs]
    plan = runs[0]["Plan"]
    result[name] = {
        "median_ms": round(statistics.median(times), 2),
        "min_ms": round(min(times), 2),
        "max_ms": round(max(times), 2),
        "rows": plan["Actual Rows"],
        "shared_buffers_hit": plan.get("Shared Hit Blocks"),
        "plan": [n for n in nodes(plan, []) if n.split()[0] in ("Seq", "Index", "Bitmap", "Hash", "Nested", "Sort", "Merge", "BitmapOr")],
    }
    print(f"{label:6} {name:50} median {result[name]['median_ms']:8.2f} ms  rows {plan['Actual Rows']:6}  {' > '.join(result[name]['plan'])}")
json.dump(result, open(f"bench-{label}.json", "w"), indent=2)
