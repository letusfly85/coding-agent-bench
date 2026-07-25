# coding-agent-bench

ローカル LLM を「コーディングエージェントのバックエンドとして使えるか」という観点で評価するためのベンチマーク。

生成されたコードが**実際にビルド・テストを通るか**まで検証することを重視している。
合成ベンチのスコア（ARC-C など）は agentic coding の実力をほとんど説明しないため、ここでは採用しない。

## 構成

```
bench/
  scripts/
    run_llama_bench.sh     # llama-bench によるスループット計測
    run_coding_task.py     # llama-server に対してコーディングタスクを投げる
  tasks/
    rust-axum-rest/
      PROMPT.md            # タスク仕様（モデルへの入力）
      verify.sh            # 生成物の検証（cargo build / cargo test）
docs/
  reports/                 # 計測レポート
results/                   # 生成物・生ログ（gitignore 対象外の要約のみコミット）
```

## 評価軸

| 軸 | 何を見るか |
|---|---|
| スループット | pp（prompt processing）/ tg（token generation）tok/s、コンテキスト長ごとの劣化 |
| VRAM | quant × context 長での実測使用量、収まる上限 |
| 一発正答率 | 生成コードが無修正で `cargo build` / `cargo test` を通るか |
| 指示追従 | 指定した出力フォーマット（ファイル分割）を守れるか |

## レポート

- [docs/reports/2026-07-25-qwen3.6-27b-neo-code-rtx5090.md](docs/reports/2026-07-25-qwen3.6-27b-neo-code-rtx5090.md)
