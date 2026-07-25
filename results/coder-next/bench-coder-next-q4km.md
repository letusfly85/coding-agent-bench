ggml_cuda_init: found 1 CUDA devices (Total VRAM: 81151 MiB):
  Device 0: NVIDIA A100-SXM4-80GB, compute capability 8.0, VMM: yes, VRAM: 81151 MiB
| model                          |       size |     params | backend    | ngl |  fa |         lm |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | --: | --: | ---------: | --------------: | -------------------: |
| qwen3next 80B.A3B Q4_K - Medium |  45.08 GiB |    79.67 B | CUDA       |  99 |   1 |       none |           pp512 |       1879.83 ± 0.14 |
| qwen3next 80B.A3B Q4_K - Medium |  45.08 GiB |    79.67 B | CUDA       |  99 |   1 |       none |          pp4096 |       1893.58 ± 4.15 |
| qwen3next 80B.A3B Q4_K - Medium |  45.08 GiB |    79.67 B | CUDA       |  99 |   1 |       none |           tg128 |        117.89 ± 1.46 |
| qwen3next 80B.A3B Q4_K - Medium |  45.08 GiB |    79.67 B | CUDA       |  99 |   1 |       none |   pp512 @ d8192 |      1843.18 ± 15.48 |
| qwen3next 80B.A3B Q4_K - Medium |  45.08 GiB |    79.67 B | CUDA       |  99 |   1 |       none |  pp4096 @ d8192 |       1855.78 ± 6.53 |
| qwen3next 80B.A3B Q4_K - Medium |  45.08 GiB |    79.67 B | CUDA       |  99 |   1 |       none |   tg128 @ d8192 |        116.77 ± 1.31 |
| qwen3next 80B.A3B Q4_K - Medium |  45.08 GiB |    79.67 B | CUDA       |  99 |   1 |       none |  pp512 @ d32768 |      1750.01 ± 15.51 |
| qwen3next 80B.A3B Q4_K - Medium |  45.08 GiB |    79.67 B | CUDA       |  99 |   1 |       none | pp4096 @ d32768 |       1744.12 ± 0.35 |
| qwen3next 80B.A3B Q4_K - Medium |  45.08 GiB |    79.67 B | CUDA       |  99 |   1 |       none |  tg128 @ d32768 |        111.12 ± 0.93 |

build: unknown (0)
