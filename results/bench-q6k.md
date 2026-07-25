ggml_cuda_init: found 1 CUDA devices (Total VRAM: 32119 MiB):
  Device 0: NVIDIA GeForce RTX 5090, compute capability 12.0, VMM: yes, VRAM: 32119 MiB
| model                          |       size |     params | backend    | ngl |  fa |            test |                  t/s |
| ------------------------------ | ---------: | ---------: | ---------- | --: | --: | --------------: | -------------------: |
| qwen35 27B Q6_K                |  20.85 GiB |    26.90 B | CUDA       |  99 |   1 |           pp512 |     3034.51 ± 213.56 |
| qwen35 27B Q6_K                |  20.85 GiB |    26.90 B | CUDA       |  99 |   1 |          pp4096 |       3096.73 ± 1.97 |
| qwen35 27B Q6_K                |  20.85 GiB |    26.90 B | CUDA       |  99 |   1 |           tg128 |         62.22 ± 0.15 |
| qwen35 27B Q6_K                |  20.85 GiB |    26.90 B | CUDA       |  99 |   1 |   pp512 @ d8192 |     2876.03 ± 141.61 |
| qwen35 27B Q6_K                |  20.85 GiB |    26.90 B | CUDA       |  99 |   1 |  pp4096 @ d8192 |       2915.95 ± 0.96 |
| qwen35 27B Q6_K                |  20.85 GiB |    26.90 B | CUDA       |  99 |   1 |   tg128 @ d8192 |         60.75 ± 0.36 |
| qwen35 27B Q6_K                |  20.85 GiB |    26.90 B | CUDA       |  99 |   1 |  pp512 @ d32768 |      2346.58 ± 77.92 |
| qwen35 27B Q6_K                |  20.85 GiB |    26.90 B | CUDA       |  99 |   1 | pp4096 @ d32768 |       2359.55 ± 0.57 |
| qwen35 27B Q6_K                |  20.85 GiB |    26.90 B | CUDA       |  99 |   1 |  tg128 @ d32768 |         56.64 ± 0.14 |

build: unknown (0)
