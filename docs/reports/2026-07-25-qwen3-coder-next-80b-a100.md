# Qwen3-Coder-Next 80B-A3B (Q4_K_M) / A100 80GB 実測レポート

計測日: 2026-07-25

前回計測した [Qwen3.6-27B-Heretic-NEO-CODE](2026-07-25-qwen3.6-27b-neo-code-rtx5090.md) の
弱点だった「出力の 8 割が thinking」「エージェント適性」を、コーダー特化 MoE で検証した。

## 対象

| | |
|---|---|
| モデル | [`Qwen/Qwen3-Coder-Next-GGUF`](https://huggingface.co/Qwen/Qwen3-Coder-Next-GGUF)（公式 GGUF） |
| 量子化 | `Q4_K_M` / 45.08 GiB / 4 分割 |
| params | **79.67B total / 3B active**（MoE: 512 エキスパート、10 活性 + 共有 1） |
| 層構成 | 48 層ハイブリッド（Gated DeltaNet ×36 + Gated Attention ×12、各層に MoE） |
| Gated Attention | 16 Q-heads / 2 KV-heads / head_dim 256 |
| context | 262,144 native |
| モード | **non-thinking 専用**（`<think>` を生成しない） |
| 推奨 sampling | temperature=1.0 / top_p=0.95 / top_k=40 |

llama.cpp はアーキテクチャを `qwen3next 80B.A3B` として認識し、追加パッチなしでロードできた。

## 計測環境

| | |
|---|---|
| GPU | NVIDIA A100-SXM4-80GB（Ampere, compute capability 8.0 / sm_80）/ 81,920 MiB |
| ドライバ | 580.159.04 |
| CUDA | 12.4（toolkit `V12.4.131`） |
| CPU | 255 論理コア（x86_64） |
| RAM | 1,007 GB |
| ストレージ | モデル領域はネットワーク FS（FUSE 経由）、ビルドはローカル overlay 20GB |
| OS | Ubuntu 24.04 |
| 推論エンジン | llama.cpp **b10107**（ソースビルド） |
| Rust | 1.97.1 |
| Go | 1.26.5 |
| Python | 3.12 / FastAPI 0.140.0 |
| Scala | scala-cli 1.15.0 / Scala 3.3.4 / OpenJDK 21.0.11 |

### セットアップ上の注意

**Blackwell 用の `sm_120` ではなく Ampere の `sm_80` を指定する:**

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release \
  -DGGML_CUDA=ON -DCMAKE_CUDA_ARCHITECTURES=80 -DLLAMA_CURL=ON
cmake --build build --config Release -j "$(nproc)"
```

**ネットワークファイルシステム上のモデルは `--load-mode none` が必須。**
llama.cpp の既定は mmap で、FUSE 上ではローダーが**完全にハングする**。
実際、4 分間で 6 MB しか読めず、プロセスは `request_wait_answer`（FUSE 応答待ち）で
停止したままだった。同じファイルを `dd` で読むと 194 MB/s 出るので、
帯域ではなく mmap のページフォルト経路が問題である。

```bash
llama-server -m model-00001-of-00004.gguf --load-mode none -ngl 99 ...
```

これで 46 GB が 45 秒でロードされる。分割 GGUF は先頭シャードを指定すれば残りは自動で読まれる。

**サーバの起動には 7 分 49 秒かかった**（ネットワーク FS からの読み出しと初期化）。
起動待ちのタイムアウトは 10 分以上を見ておくこと。ローカル NVMe ならこの限りではない。

**ダウンロードは並列接続で。** `aria2c -x16 -s16` で 85 MB/s 前後、48.4 GB を約 10 分で取得できた。
HF のフォルダ名は `Qwen3-Coder-Next-Q4_K_M/` であり、`Q4_K_M/` ではない（404 になる）。

---

## 1. スループット

`llama-bench --load-mode none -ngl 99 -fa 1 -r 2`。

| test | **Coder-Next 80B / A100** | 参考: 27B / RTX 5090 |
|---|---:|---:|
| pp512 | 1,879.8 ± 0.1 | 3,560.8 |
| pp4096 | 1,893.6 ± 4.2 | 3,684.9 |
| **tg128** | **117.89 ± 1.46** | 76.80 |
| pp512 @ d8192 | 1,843.2 ± 15.5 | 3,375.4 |
| tg128 @ d8192 | 116.77 ± 1.31 | 74.60 |
| pp512 @ d32768 | 1,750.0 ± 15.5 | 2,693.1 |
| **tg128 @ d32768** | **111.12 ± 0.93** | 68.40 |

- **生成が 53% 速い**。80B だが active 3B なので、1 トークンあたり読み出すパラメータが少なく、メモリ帯域律速の生成では大幅に有利。
- **32k 深度での生成劣化はわずか 5.7%**（27B は 11%）。
- prompt processing は 47% 遅い。こちらは計算律速で、MoE のルーティングが乗る。

> **比較の注意**: GPU が異なる（A100 80GB vs RTX 5090 32GB）ため、この差はモデルと
> ハードウェアの両方を含む。モデル単体の比較ではない。

## 2. VRAM

| | |
|---|---:|
| 重みのみ | 46,421 MiB |
| ctx 32,768（KV f16） | **47,501 MiB** |

KV は 1 トークンあたり約 24 KiB（Gated Attention 12 層 × 2 KV-heads × 256 dim × K/V × 2 byte）。
262,144 トークンでも 6 GiB 程度で、**80GB なら余裕でネイティブ全長が載る**。

**48GB クラスでは載らない**（重みだけで 46.4 GB）。64GB 以上、実用上は 80GB を見ること。

---

## 3. コード生成タスク

同一仕様の REST API を 4 言語で生成させ、**実際にビルドとテストが通るか**で判定。各 5 試行。
（タスク定義と検証スクリプトは 27B のレポートと同一）

### 3.1 速度とトークン消費 — ここが最大の差

| タスク | input | output 中央値 | 所要時間 中央値 | tok/s | **thinking 比率** |
|---|---:|---:|---:|---:|---:|
| Python / FastAPI | 584 | **877** | **7.6 s** | 120.0 | **0%** |
| Scala / http4s | 787 | 1,681 | 14.3 s | 119.9 | **0%** |
| Rust / axum | 677 | 1,785 | 15.5 s | 119.6 | **0%** |
| Go / net/http | 587 | 2,112 | 17.9 s | 119.2 | **0%** |

27B との比較:

| | Coder-Next | 27B thinking | 倍率 |
|---|---:|---:|---:|
| Python 出力トークン | 877 | 3,592 | **1/4.1** |
| Python 所要時間 | 7.6 s | 48.7 s | **1/6.4** |
| Rust 出力トークン | 1,785 | 9,368 | **1/5.2** |
| Rust 所要時間 | 15.5 s | 127.8 s | **1/8.2** |
| thinking 比率 | **0%** | 79.6% | — |

**non-thinking 専用という仕様が、そのままトークン効率に出た。**
27B の「出力の 8 割が thinking」という最大の弱点は完全に解消されている。

しかも回答本体の文字数はほぼ同等（Rust で 6,810〜7,641 字 vs 27B の 6,714 字）で、
**同じ量のコードを 1/5 のトークンで出している**。出力を削って短くしたわけではない。

### 3.2 pass@1 — サンプリング設定で結果が変わる

モデルカードの推奨値は `temperature=1.0 / top_p=0.95 / top_k=40` だが、
27B の計測では coding 用の `0.6 / 0.95 / 20` を使っていた。
条件が揃わないため**両方で 5 試行ずつ**計測した。

| 言語 | temp=1.0（カード推奨） | **temp=0.6** | 参考: 27B @ 0.6 |
|---|---|---|---|
| **Python** / FastAPI | build 5/5 / test **2/5** | build 5/5 / test **4/5** | 5/5 / **5/5** |
| **Go** / net/http | build 5/5 / test **2/5** | build 5/5 / test **3/5** | 5/5 / **5/5** |
| **Rust** / axum | build 2/5 / test **0/5** | build 1/5 / test **0/5** | build 4/5 / 0/5 |
| **Scala** / http4s | build 0/5 / test **0/5** | build 0/5 / test **0/5** | 0/5 / 0/5 |

**`temperature=1.0` は pass@1 を明確に下げる。** Python は 2/5 → 4/5、Go は 2/5 → 3/5 に改善した。
モデルカードの推奨値は対話や多様性を想定したものと思われ、
**決定論的な正答を測るベンチや、一発で正しいコードが欲しい用途には不適**である。

条件を揃えると 27B との差は n=5 では有意と言える水準ではない
（Python 4/5 vs 5/5、Go 3/5 vs 5/5）。
コーダー特化 80B が 27B に対して単発生成で明確に勝っている、とは本計測では言えなかった。

### 3.3 失敗するのはテストコード

27B と同じく、**壊れるのは実装ではなくテスト側**だった。ただし壊れ方が違う。

**Go は実行時のテスト失敗**（コンパイルは 5/5 通る）。生成されたテストはこうなっていた:

```go
// POST でタスクを作る
App().ServeHTTP(w, req)          // ← App() 呼び出し①

// 作ったタスクを GET する
req = httptest.NewRequest("GET", "/tasks/1", nil)
App().ServeHTTP(w, req)          // ← App() 呼び出し②。別インスタンス
```

`App()` は仕様どおり毎回新しい状態を返すので、②に①のタスクは存在せず 404 になる。
**実装は正しく、テストが自分の書いた実装の不変条件を守れていない。**

**Rust は 27B より build 成功率が低い**（1〜2/5 vs 4/5）。
`fn main` の欠落（`E0601`）が 5 回中 1 回、構文エラー、`E0308` 型不一致など。
ただし生成行数は 233〜253 行で 27B（222 行）と同等であり、
出力トークンが 1/5 になったことによる「削りすぎ」ではない。

---

## 4. tool calling とエージェントループ

### 4.1 単発 tool call の正確性（n=30）

| 指標 | **Coder-Next** | 27B |
|---|---|---|
| **正しいツールの選択** | **30 / 30（100%）** | 27 / 30（90%） |
| JSON パース失敗 | 0 | 0 |
| スキーマ違反 | 0 | 0 |
| 存在しないツール名 | 0 | 0 |
| 不要な場面での誤呼び出し | 0 / 6 | 0 / 6 |
| **レイテンシ中央値** | **0.45 秒** | 1.16 秒 |
| completion トークン中央値 | **33** | 72 |

**満点。** 27B が 3 回とも間違えた「テストスイートを実行して結果を教えて」
（`run_command` を選ぶべきところで `list_dir` を選んでいた）も正しく処理している。

レイテンシは 27B の 1/2.6、トークンは 1/2.2。エージェントのターン単価が明確に安い。

### 4.2 実タスクでのエージェントループ

実際にファイルシステムを操作し、本物の pytest を走らせるサンドボックスで、
バグ修正タスクを最大 30 ターンまで自律実行させた。

| タスク | エピソード | 完遂 | ターン数 | ターン遅延中央値 |
|---|---:|---:|---|---:|
| `agent-fix-bug`（1 ファイル 1 バグ） | 10 | **10 / 10** | 6〜13 | 0.53 秒 |
| `agent-multi-bug`（2 ファイル 3 バグ） | 5 | **5 / 5** | 9〜13 | 0.72 秒 |
| **合計** | **15** | **15 / 15** | **143 ターン** | — |

**不正なツール呼び出しは 143 ターン中 0 件。**

27B（18/18、166 ターン、malformed 0）と完遂率は同等だが、
ターン遅延が約半分（0.53 秒 vs 0.91 秒）。

---

## 5. 結論

### エージェント用途では明確に上、単発生成では同等

| 観点 | Coder-Next 80B-A3B | 27B thinking | 判定 |
|---|---|---|---|
| tool call 正答率 | **30/30 (100%)** | 27/30 (90%) | **Coder-Next** |
| tool call レイテンシ | **0.45 秒 / 33 tok** | 1.16 秒 / 72 tok | **Coder-Next** |
| エージェント完遂率 | 15/15（143 ターン、malformed 0） | 18/18（166 ターン、malformed 0） | 同等 |
| エージェントのターン遅延 | **0.53 秒** | 0.91 秒 | **Coder-Next** |
| 生成速度 | **117.9 tok/s** | 76.8 tok/s | **Coder-Next** |
| 1 タスクのトークン | **877〜2,112** | 3,592〜9,368 | **Coder-Next** |
| thinking 比率 | **0%** | 79.6% | **Coder-Next** |
| pass@1（条件を揃えて） | Py 4/5・Go 3/5・Rust 0/5・Scala 0/5 | Py 5/5・Go 5/5・Rust 0/5・Scala 0/5 | 27B がやや上、ただし n=5 |

**エージェントのバックエンドとして使うなら Coder-Next を選ぶ理由がはっきりある。**
1 ターンあたり 0.45 秒・33 トークンという安さは、20〜50 ターン回すループで効いてくる。
27B で「出力の 8 割が thinking」だった問題は構造的に解消されている。

一方、**「REST API を一発で書かせる」用途では 27B に対する優位は確認できなかった。**
80B のコーダー特化モデルが 27B に負けうる、という結果は直感に反するが、
n=5 での差なので「明確に劣る」とも言えない。

### サンプリング設定に注意

**モデルカードの `temperature=1.0` をそのまま使わないこと。**
pass@1 が Python で 4/5 → 2/5、Go で 3/5 → 2/5 に落ちる。
コードを一発で当てたい用途では `0.6` を使うべきである。
（tool calling とエージェントループは 1.0 でも満点だったので、こちらは推奨値のままでよい。）

### 弱点は 27B と共通

**Rust と Scala は 0/5 で、27B とまったく同じ。**
モデルを大きくしても、コーダー特化にしても、
**学習データが薄い言語・ライブラリの壁は動かなかった**。
これは 27B のレポートで述べた「自分が使うスタックで試験せよ」という結論を補強する。

### 必要な VRAM

| VRAM | 判定 |
|---|---|
| 48GB | ❌ 重みだけで 46.4 GB。実質不可 |
| 64GB | △ 載るが余裕がない |
| **80GB** | ⭕️ **推奨**。ctx 32k で 47.5 GB、262k でも 53 GB 程度の見込み |

## 6. 再現手順

```bash
# 1. llama.cpp（A100 / Ampere 向け）
cmake -B build -DCMAKE_BUILD_TYPE=Release \
  -DGGML_CUDA=ON -DCMAKE_CUDA_ARCHITECTURES=80 -DLLAMA_CURL=ON
cmake --build build --config Release -j "$(nproc)"

# 2. モデル取得（4 シャード、48.4 GB）
BASE=https://huggingface.co/Qwen/Qwen3-Coder-Next-GGUF/resolve/main/Qwen3-Coder-Next-Q4_K_M
for i in 00001 00002 00003 00004; do
  F=Qwen3-Coder-Next-Q4_K_M-${i}-of-00004.gguf
  aria2c -x16 -s16 -k8M --continue=true -o "$F" "$BASE/$F"
done

# 3. サーバ起動（ネットワーク FS なら --load-mode none 必須、起動に 8 分程度）
llama-server -m Qwen3-Coder-Next-Q4_K_M-00001-of-00004.gguf \
  --load-mode none -ngl 99 -fa 1 -c 32768 -np 1 --jinja \
  --host 127.0.0.1 --port 8080

# 4. 計測（sampling はモデルカード推奨値）
TEMP=1.0 TOP_P=0.95 TOP_K=40 N=5 bench/scripts/run_repeat.sh
bench/scripts/tool_call_fidelity.py --reps 3 --temperature 1.0 --top-k 40 --out tf.json
bench/scripts/agent_loop.py --task-dir bench/tasks/agent-multi-bug/project \
  --work-root /tmp/aw --episodes 5 --temperature 1.0 --top-k 40 --out al.json
```

## 7. 本計測でカバーしていないこと

- **262k フルコンテキストでの VRAM 実測** — 起動に 8 分かかるため未実施。
  KV の理論値からは 53 GB 程度で収まる見込み
- **vLLM との比較** — 公式 FP8（`Qwen/Qwen3-Coder-Next-FP8`）は重み約 80 GB で
  A100 80GB 単体には載らない。MoE は vLLM の continuous batching と相性が良いはずで、
  バッチ実行なら別の結論になりうる
- 27B との比較は **GPU が異なる**ため、モデル単体の差ではない
- タスクは REST API 生成とバグ修正の 2 種類のみ
