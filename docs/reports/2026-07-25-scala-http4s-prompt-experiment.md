# Scala / http4s はプロンプトで救えるか — 5 変種 × 5 試行

計測日: 2026-07-25

## 背景

先行する 2 つの計測で、**Scala + http4s だけが一貫して 0/5** だった。

| モデル | Python | Go | Rust | **Scala** |
|---|---|---|---|---|
| [Qwen3.6-27B-Heretic-NEO-CODE](2026-07-25-qwen3.6-27b-neo-code-rtx5090.md) | 5/5 | 5/5 | 0/5 | **0/5** |
| [Qwen3-Coder-Next 80B-A3B](2026-07-25-qwen3-coder-next-80b-a100.md) | 4/5 | 3/5 | 0/5 | **0/5** |

27B ではコンパイルエラーを返す修正ループを 3 ラウンド回しても収束せず、
コンパイラが `import org.http4s.circe.CirceEntityCodec.circeEntityDecoder` と
**正解を文字列で明示しているのに採用できなかった**。

モデルを 27B → 80B に、汎用 → コーダー特化に変えても動かなかった。
**残る手段はプロンプトだけ**である。そこで「どこまで情報を与えれば通るのか」を測った。

## 方法

同一タスク（http4s による REST API + munit の統合テスト）に対し、
**段階的に情報量を増やした 5 つのプロンプト**を用意し、各 5 試行。

| 変種 | 与えた情報 |
|---|---|
| **a-baseline** | 元のプロンプト（依存宣言とエンドポイント仕様のみ） |
| **b-imports** | + **正しい import ブロック全体**（`CirceEntityCodec._` を含み、なぜ `org.http4s.circe._` では駄目かの説明つき） |
| **c-cheatsheet** | + **観測済みの罠 4 件**（ip4s の `host"..."` 補間子、`Map` 削除のラムダ、`Ref.modify` と `updateAndGet` の使い分け、パスパラメータ記法） |
| **d-skeleton** | **コンパイルの通るスケルトン**を渡し、`???` を埋めさせる（他変種より明確に易しい） |
| **e-worked-example** | **別リソース（notes）で動作する完全な http4s 実装**を提示し、同じ構造で tasks を書かせる |

- モデル: Qwen3-Coder-Next 80B-A3B `Q4_K_M`
- sampling: `temperature=0.6 / top_p=0.95 / top_k=20`（coding 用。この設定のほうが pass@1 が高いことは先行計測で確認済み）
- 判定: `scala-cli compile` と `scala-cli test` が通るか

## 結果

| 変種 | 入力トークン | **build** | **test** |
|---|---:|---:|---:|
| a-baseline | 787 | 0/5 | 0/5 |
| b-imports | 933 | 0/5 | 0/5 |
| c-cheatsheet | 1,160 | 0/5 | 0/5 |
| d-skeleton | 1,096 | 0/5 | 0/5 |
| **e-worked-example** | 1,368 | **1/5** | 0/5 |

**入力トークンを 74% 増やして、テスト通過は 0/25 のまま。**
唯一の前進は、動作する実装例を丸ごと見せた e で build が 1 回通ったことだけである。

## 何が起きているか — エラーの層が移動する

情報を足しても失敗数は変わらないが、**失敗の種類は明確に変化する**。

| 変種 | 支配的なエラー | 層 |
|---|---|---|
| a-baseline | `value circe is not a member` | **import パス** |
| b-imports | `Not found: Decoder` / `Encoder`、`value use is not a member` | **名前解決** |
| c-cheatsheet | `Not found: deleteTask` / `getTask`（自分が定義していない関数を呼ぶ）、`Found: IO[...]` | **自コードの整合性** |
| d-skeleton | `Found: cats.effect.IO[...]`、`withFilter is not a member` | **効果型の合成** |
| e-worked-example | `Found: IO[...]`、`value pure is not a member` | **効果型の合成** |

浅い層の誤りは、情報を与えれば消える。**しかし消えた分だけ深い層の誤りが露出し、コンパイルは通らない。**

d-skeleton で出た代表的なエラー:

```
Found:    cats.effect.IO[org.http4s.Response[cats.effect.IO]]
Required: org.http4s.Response[cats.effect.IO]
            case Some(task) => Ok(task)

value withFilter is not a member of cats.effect.IO[UpdateTaskReq]
          UpdateTaskReq(title, done) <- req.as[UpdateTaskReq]
```

1 つ目は、`Ok(x)` が既に `IO[Response[IO]]` を返すことを理解せず、`map` の中で使って二重に包んでいる。
2 つ目は、`for` 内包表記で case class パターンによる分解を書いており、
`IO` が持たない `withFilter` を要求してしまっている。

どちらも **import を教えれば済む種類の誤りではない**。
`IO` という型がどう合成されるかを追えていない。

さらに c-cheatsheet と d-skeleton では `Not found: deleteTask`, `Not found: routes`, `Not found: response` のように、
**自分が定義していない識別子を参照する**エラーも出た。自コードのスコープを保持できていない。

## 唯一 build が通った試行も、テストで落ちた

e-worked-example の run1 は `main.scala` のコンパイルに成功したが、
`main.test.scala` が落ちた。原因は:

```scala
import io.circe.parser._    // circe-parser は using dep に宣言されていない
```

**宣言されていない依存を使うテストを書いた。** 実装は通ったのにテストで落ちる、
という形は、他言語で観測したパターンとまったく同じである
（Rust: 実装 4/5 通過・テスト 0/5、Go: コンパイル 5/5・テスト実行時に失敗）。

**壊れるのは常にテストコード**という傾向は、言語・モデルを問わず一貫している。

## 結論

### プロンプトでは解決しない

情報量を 74% 増やしても pass@1 は 0/25 のまま。
**Scala + http4s + cats-effect の失敗は、プロンプトで供給できる種類の知識不足ではない。**

段階的な変種が示したのは、この構成が要求するものが階層をなしているということ:

1. import パスの記憶 ← プロンプトで供給できる
2. ライブラリ API の記憶 ← プロンプトで供給できる
3. **効果型（`IO`）の合成規則の運用** ← **供給できない**
4. **自分が書いたコードのスコープ保持** ← **供給できない**

1 と 2 を埋めても 3 と 4 が残り、そこで止まる。

### fine-tuning への含意

以前「fine-tuning すれば変わるか」を検討した際、
観測されていた失敗（誤った import、ip4s の型）が**表面的な API 知識の欠落**に見えたため
「fine-tuning が効く領域」と評価した。**本実験はその評価を修正する。**

プロンプトで API 知識を与えると、残るのは効果型の合成と自コードの整合性である。
これらは事例の暗記ではなく型システムの運用能力であり、
**LoRA 規模の追加学習で埋まる保証はない**。

fine-tuning を検討するなら、まず本実験の b/c 変種を回して
「知識を与えれば通るのか」を確認するべきで、
本モデルに関してはその答えが **No** だった。

### 実務的な判断

**Scala + http4s + cats-effect のコード生成に、この世代のローカルモデルは使えない。**
プロンプト工夫の余地は測った範囲では尽きている。取りうる選択肢は:

- より単純なスタックに変える（例: Scala でも純粋な `cats.effect` を使わない構成）
- 人間が骨格を書き、モデルには局所的な補完だけさせる
- Scala 部分は商用の大規模モデルに投げる

なお **e-worked-example（動作例を丸ごと見せる）だけは 0/5 → build 1/5 の前進があった**ので、
どうしてもローカルで回すなら、リポジトリ内の既存の動作コードを
コンテキストに入れる方式が最も見込みがある。

## 限界

- **1 モデル（Qwen3-Coder-Next 80B-A3B `Q4_K_M`）のみ**。27B では変種実験を実施していない
  （計測マシンを解放済みのため）。27B のベースラインは 0/5 + 修正 3 ラウンド不収束
- 各変種 n=5、合計 25 試行。build 1/5 という差は n=5 では有意ではない
- タスクは REST API 1 種類。他の Scala タスク（データ処理、純粋関数のみの実装など）では
  結果が異なる可能性がある。**効果型を使わない Scala なら通るかもしれない**が未検証
- http4s / cats-effect 固有の結果であり、Scala 一般の結論ではない

## 再現手順

```bash
# llama-server を起動しておく（sampling はリクエスト単位で送るのでサーバ側設定は不問）
TEMP=0.6 TOP_P=0.95 TOP_K=20 TASK=scala-http4s-rest N=5 \
  OUT=/path/to/results/scala-variants \
  bench/scripts/run_prompt_variants.sh

# 単一変種だけ回す
VARIANTS=e-worked-example TEMP=0.6 TASK=scala-http4s-rest N=5 \
  bench/scripts/run_prompt_variants.sh
```

プロンプト変種は `bench/tasks/scala-http4s-rest/variants/` に置いてある。
