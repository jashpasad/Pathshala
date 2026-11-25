# Model: Llama 3.2 1B Instruct on ExecuTorch

| Item | Choice | Why |
|---|---|---|
| Model | Llama 3.2 **1B** Instruct, SpinQuant (INT4 weights, group 32; INT8 dynamic activations) | Meta's mobile-quantised checkpoint. Officially supports Hindi. Fits 6–8 GB phones |
| Runtime | ExecuTorch **1.5.1** (`org.pytorch:executorch-android`) | Reference runtime required by the challenge |
| Backend | XNNPACK on the Arm CPU, with KleidiAI micro-kernels (on by default for Arm) | Works on MediaTek and Qualcomm alike. MediaTek shipped about half of Indian phones in 2025–26 |
| Context | 2048 tokens (`export.max_seq_length/max_context_length`) | Tutor sessions + grounded prompts. Default exports use 128 |
| Optional | 3B SpinQuant on 12 GB+ phones | Better Hindi and tutoring quality. The app recommends it from detected RAM |

Published reference numbers, OnePlus 12 (ExecuTorch README; KleidiAI enabled, prompt length 64):

| Model | Decode tok/s | TTFT s | Prefill tok/s | .pte MiB | RSS MiB |
|---|---|---|---|---|---|
| 1B BF16 | 19.2 | 1.0 | 60.3 | 2,358 | 3,185 |
| **1B SpinQuant** | **50.2** | **0.3** | **260.5** | **1,083** | **1,921** |
| 3B SpinQuant | 19.7 | 0.7 | 89.7 | 2,435 | 3,726 |

**Your own numbers:** open the app → Engine → "Run 3 PathShala workloads". It records TTFT and prefill/decode tok/s from ExecuTorch's runner stats, plus process RSS, on PathShala's real prompts. Paste them into the report's hardware-utilisation table (Section 6.2).

## Steps
```bash
./model/export_llama.sh            # or USE_PREBUILT=1 ./model/export_llama.sh for the community .pte
./model/push_to_phone.sh ~/pathshala-model/llama3_2_1b_spinquant_ctx2048.pte ~/pathshala-model/tokenizer.model
```
You can also copy both files to the phone and use Engine → "Import file…" (system file picker, no permissions needed).
