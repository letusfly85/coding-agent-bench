ggml_cuda_init: found 1 CUDA devices (Total VRAM: 32119 MiB):
  Device 0: NVIDIA GeForce RTX 5090, compute capability 12.0, VMM: yes, VRAM: 32119 MiB
| model                          |       size |     params | backend    | ngl |  fa |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | --: | --: | --------------: | -------------------: |
| qwen35 27B Q4_K - Medium       |  15.69 GiB |    26.90 B | CUDA       |  99 |   1 |           pp512 |     3560.84 ± 407.87 |
| qwen35 27B Q4_K - Medium       |  15.69 GiB |    26.90 B | CUDA       |  99 |   1 |          pp4096 |       3684.89 ± 3.04 |
| qwen35 27B Q4_K - Medium       |  15.69 GiB |    26.90 B | CUDA       |  99 |   1 |           tg128 |         76.80 ± 0.24 |
| qwen35 27B Q4_K - Medium       |  15.69 GiB |    26.90 B | CUDA       |  99 |   1 |   pp512 @ d8192 |     3375.35 ± 186.91 |
| qwen35 27B Q4_K - Medium       |  15.69 GiB |    26.90 B | CUDA       |  99 |   1 |  pp4096 @ d8192 |       3431.89 ± 0.25 |
| qwen35 27B Q4_K - Medium       |  15.69 GiB |    26.90 B | CUDA       |  99 |   1 |   tg128 @ d8192 |         74.60 ± 0.54 |
| qwen35 27B Q4_K - Medium       |  15.69 GiB |    26.90 B | CUDA       |  99 |   1 |  pp512 @ d32768 |     2693.11 ± 121.75 |
| qwen35 27B Q4_K - Medium       |  15.69 GiB |    26.90 B | CUDA       |  99 |   1 | pp4096 @ d32768 |       2698.73 ± 1.12 |
| qwen35 27B Q4_K - Medium       |  15.69 GiB |    26.90 B | CUDA       |  99 |   1 |  tg128 @ d32768 |         68.40 ± 0.18 |

build: unknown (0)
