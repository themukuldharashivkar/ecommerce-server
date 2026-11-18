#!/usr/bin/env bash
# Latency of the catalog and product-detail endpoints with ApacheBench (ab ships with macOS).
# Start the app first, once with CACHE_TYPE=none and once with CACHE_TYPE=redis, and run this against each.
#
# Usage: scripts/benchmark/cache_bench.sh <label> [base_url] [product_id]
# Env:   REQUESTS (default 2000), WARMUP (default 300), RUNS (default 3)
set -euo pipefail

LABEL=${1:?label, e.g. no-cache or redis}
BASE=${2:-http://localhost:5454}
PRODUCT_ID=${3:-1}
REQUESTS=${REQUESTS:-2000}
WARMUP=${WARMUP:-300}
RUNS=${RUNS:-3}

# the request the shop page sends (12 per page) and a product page
CATALOG="$BASE/api/products?color=&size=&minPrice=0&maxPrice=100000&minDiscount=0&category=men_jeans&stock=&sort=price_low&pageNumber=0&pageSize=12"
DETAIL="$BASE/api/products/id/$PRODUCT_ID"

measure() { # name url concurrency
  local name=$1 url=$2 c=$3
  ab -q -k -n "$WARMUP" -c "$c" "$url" > /dev/null
  for run in $(seq 1 "$RUNS"); do
    csv=$(mktemp)
    # -e writes the percentile table with sub-millisecond precision (the text report rounds to whole ms)
    out=$(ab -q -k -n "$REQUESTS" -c "$c" -e "$csv" "$url")
    p50=$(awk -F, '$1=="50" {printf "%.2f", $2}' "$csv")
    p95=$(awk -F, '$1=="95" {printf "%.2f", $2}' "$csv")
    p99=$(awk -F, '$1=="99" {printf "%.2f", $2}' "$csv")
    rm -f "$csv"
    rps=$(echo "$out" | awk '/Requests per second/ {print $4}')
    failed=$(echo "$out" | awk '/Failed requests/ {print $3}')
    non2xx=$(echo "$out" | awk '/Non-2xx responses/ {print $3}')
    printf "%-9s %-8s c=%-3s run %s  p50 %6s ms  p95 %6s ms  p99 %6s ms  %8s req/s  failed %s  non-2xx %s\n" \
      "$LABEL" "$name" "$c" "$run" "$p50" "$p95" "$p99" "$rps" "$failed" "${non2xx:-0}"
  done
}

measure catalog "$CATALOG" 1
measure catalog "$CATALOG" 10
measure detail "$DETAIL" 1
measure detail "$DETAIL" 10
