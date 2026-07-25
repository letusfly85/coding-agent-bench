# results/

`bench/scripts/` の実行結果。生成物 (`project/`) とモデルの生出力 (`raw/`) を含む。

- `bench-*.md` — llama-bench のスループット計測
- `vram-*.tsv` — context 長ごとの VRAM 実測
- `tasks/<task>/` — 1 回目の生成
- `tasks/<task>-repairN/` — コンパイルエラーを返して再生成した N 回目

ビルド成果物 (`target/`, `__pycache__/`, `.scala-build/`) は `.gitignore` 済み。
