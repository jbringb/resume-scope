# Grafana dashboard

[`resumescope-dashboard.json`](resumescope-dashboard.json) visualizes the app's Prometheus metrics
(`GET /actuator/prometheus`): analysis run outcomes, LLM token/cost consumption, LLM call latency
(p50/p95/p99) and error rate, plus the default Micrometer HTTP/JVM metrics that come free with
Spring Boot Actuator.

## Try it locally

```bash
docker run -d --name resumescope-prometheus -p 9090:9090 \
  -v "$(pwd)/deploy/grafana/prometheus.yml:/etc/prometheus/prometheus.yml" \
  prom/prometheus

docker run -d --name resumescope-grafana -p 3000:3000 grafana/grafana
```

Point Prometheus at the running app (`prometheus.yml`):

```yaml
scrape_configs:
  - job_name: resumescope
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:8086"]
```

Then in Grafana (`http://localhost:3000`, default `admin`/`admin`): add a **Prometheus** datasource
pointing at `http://host.docker.internal:9090`, then **Dashboards → New → Import** and upload
`resumescope-dashboard.json`.

## What each panel shows

| Panel | Metric | Why it's here |
|---|---|---|
| Analysis Runs | `resumescope_analysis_runs_total{status}` | Completed vs. failed run rate — the core business KPI |
| LLM Tokens Consumed | `resumescope_analysis_tokens_total{type}` | Prompt vs. completion token throughput |
| Estimated LLM Spend | `resumescope_analysis_llm_cost_eur_total` | Cumulative cost since app start (complements the app's own per-key monthly budget, exposed via `GET /api/usage/monthly`) |
| LLM Call Latency | `resumescope_analysis_llm_call_duration_seconds` | p50/p95/p99 of the raw OpenAI call — the one genuinely unpredictable external dependency |
| LLM Call Error Rate | same, `outcome="error"` | Ratio of failed LLM calls |
| HTTP Request Rate / p95 Latency | `http_server_requests_seconds_*` | Free with Actuator + WebFlux — request volume and latency by route |
| JVM Heap / CPU | `jvm_memory_used_bytes`, `process_cpu_usage` | Standard runtime health |

All custom metric tags (`status`, `type`, `outcome`) are fixed low-cardinality enum values — never
an id — so the label set can never grow unbounded regardless of traffic.
