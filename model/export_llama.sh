#!/usr/bin/env bash
# =====================================================================================
# PathShala - export Llama 3.2 1B Instruct (SpinQuant INT4 weights / INT8 dynamic act.)
# to an ExecuTorch .pte for the Arm CPU (XNNPACK backend; KleidiAI kernels are on by
# default for Arm in XNNPACK).  Run on Linux or macOS (Apple silicon), Python 3.10-3.12,
# ~16 GB RAM.  You need a Hugging Face account that has accepted the Llama 3.2 licence.
#
# The runtime in the app is org.pytorch:executorch-android:1.5.1, so export with the
# SAME ExecuTorch release to avoid .pte/runtime version mismatches.
# =====================================================================================
set -euo pipefail
WORK=${WORK:-$HOME/pathshala-model}
ET_TAG=${ET_TAG:-v1.5.1}            # must match app/build.gradle.kts executorch-android version
CTX=${CTX:-2048}                    # context window baked into the KV cache (default exports use 128!)
mkdir -p "$WORK" && cd "$WORK"

# ---- 0. Optional shortcut: pre-exported community .pte (may target an older runtime) ----
if [[ "${USE_PREBUILT:-0}" == "1" ]]; then
  huggingface-cli download executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET \
      --local-dir prebuilt
  echo "Pre-built files in $WORK/prebuilt. If the app reports a load/version error, run without USE_PREBUILT."
  exit 0
fi

# ---- 1. ExecuTorch source at the matching release ----
if [[ ! -d executorch ]]; then
  git clone https://github.com/pytorch/executorch.git
fi
cd executorch
git fetch --tags && git checkout "$ET_TAG"
git submodule sync && git submodule update --init --recursive
python3 -m venv .venv && source .venv/bin/activate
./install_executorch.sh
examples/models/llama/install_requirements.sh

# ---- 2. Meta's SpinQuant checkpoint (original/consolidated format) ----
huggingface-cli download meta-llama/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8 \
    --local-dir "$WORK/spinquant"
CKPT=$(ls "$WORK"/spinquant/consolidated*.pth | head -1)
PARAMS="$WORK/spinquant/params.json"
cp "$WORK/spinquant/tokenizer.model" "$WORK/tokenizer.model"

# ---- 3. Export (XNNPACK, SpinQuant config from the ExecuTorch repo) ----
# If your ExecuTorch version rejects the export.* keys, see
# extension/llm/export/config/llm_config.py for the field names.
python -m extension.llm.export.export_llm \
  --config examples/models/llama/config/llama_xnnpack_spinquant.yaml \
  +base.model_class="llama3_2" \
  +base.checkpoint="$CKPT" \
  +base.params="$PARAMS" \
  export.max_seq_length=$CTX \
  export.max_context_length=$CTX \
  export.output_name="$WORK/llama3_2_1b_spinquant_ctx${CTX}.pte"

ls -lh "$WORK"/*.pte "$WORK/tokenizer.model"
echo "Done. Next: ./model/push_to_phone.sh $WORK/llama3_2_1b_spinquant_ctx${CTX}.pte $WORK/tokenizer.model"
