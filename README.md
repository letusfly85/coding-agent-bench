# coding-agent-bench

ローカル LLM を「コーディングエージェントのバックエンドとして使えるか」という観点で評価するためのベンチマーク。

生成されたコードが**実際にビルド・テストを通るか**まで検証することを重視している。
合成ベンチのスコア（ARC-C など）は agentic coding の実力をほとんど説明しないため、ここでは採用しない。

## 構成

```
bench/
  scripts/
    run_llama_bench.sh     # llama-bench によるスループット計測
    vram_sweep.sh          # context 長 × KV 型ごとの VRAM 実測
    run_coding_task.py     # llama-server に対してコーディングタスクを投げる
    run_repeat.sh          # 各タスクを N 回繰り返して pass@1 を出す
    run_repair.sh          # ビルドエラーを返して再生成させる
    tool_call_fidelity.py  # 単発 tool call の JSON/スキーマ/ツール選択の正確性
    agent_loop.py          # 実ファイルを操作する多ターンエージェントループ
    run_prompt_variants.sh # 同一タスクをプロンプト変種ごとに N 回実行
    ensemble_agent.py      # 指揮役とコード役を別モデルに分けたエージェントループ
  tasks/
    rust-axum-rest/        # 生成タスク（他に go / python / scala）
      PROMPT.md            # タスク仕様（モデルへの入力）
      verify.sh            # 生成物の検証（cargo build / cargo test）
    agent-fix-bug/         # エージェント用: 1 ファイル 1 バグ
    agent-multi-bug/       # エージェント用: 2 ファイル 3 バグ
    agent-{go,rust,scala}-rest/  # エージェント用: テスト固定、実装のみ書かせる
      project/             # エージェントに渡す。テストは改変不可
      reference/           # 解けることを検証するための参照実装（モデルには渡さない）
    scala-http4s-rest/variants/  # 情報量を段階的に増やしたプロンプト 5 種
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
| tool calling | tool call の JSON 妥当性・スキーマ準拠・ツール選択の正確性 |
| エージェント完遂率 | 実ファイルを操作する多ターンループでタスクを完遂できるか |

## レポート

- [2026-07-25 Qwen3.6-27B-NEO-CODE / RTX 5090 32GB](docs/reports/2026-07-25-qwen3.6-27b-neo-code-rtx5090.md) — 4 言語 × 5 試行、tool calling、エージェントループ
- [2026-07-25 Qwen3-Coder-Next 80B-A3B / A100 80GB](docs/reports/2026-07-25-qwen3-coder-next-80b-a100.md) — non-thinking MoE。上記との比較つき
- [2026-07-25 Scala / http4s はプロンプトで救えるか](docs/reports/2026-07-25-scala-http4s-prompt-experiment.md) — 5 変種 × 5 試行の切り分け。第 2 部でエージェントループも検証
- [2026-07-25 指揮役とコード役を分ける](docs/reports/2026-07-25-ensemble-orchestrator-coder.md) — 2 モデルアンサンブル。Go / Rust × 3 構成

## 注意

**pass@1 を 1 試行で測らないこと。** 本ベンチの初回計測では n=1 の結果が n=5 の結果と
正反対になった（Rust: n=1 で pass → n=5 で 0/5、Go: n=1 で fail → n=5 で 5/5）。
量子化間の差より試行間の分散のほうが大きい。`run_repeat.sh` を使うこと。

**サンプリング設定を揃えること。** モデルカードの推奨値はモデルごとに違う
（Qwen3-Coder-Next は temp=1.0、Qwen3.6-27B の coding preset は 0.6）。
揃えずに比較すると、モデルの差ではなくサンプリングの差を測ってしまう。
実際 Coder-Next は temp=1.0 だと Python の pass@1 が 4/5 → 2/5 に落ちる。
`TEMP` / `TOP_P` / `TOP_K` 環境変数で指定する。

**ネットワークファイルシステム上のモデルは `LOAD_MODE=none` を指定すること。**
llama.cpp 既定の mmap は FUSE 上でローダーごとハングする。
