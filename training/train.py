#!/usr/bin/env python3
"""QLoRA fine-tune of Qwen3-1.7B into `baritone-brain`: a tiny, fast command brain that maps a
player's plain-English goal straight to a Baritone tool call - no tool schemas in the prompt
(they are baked into the weights), so the prompt drops from ~15k tokens to ~200 and the whole
model fits on the 8GB GPU even with Minecraft running.

Run with:  ./run_train.sh          (do NOT run until you mean it; ~1-2h on an RTX 4070 Laptop)

Pipeline: load data/synthetic.jsonl + data/harvested.jsonl -> chat-format (Qwen3 tool-call
style) -> QLoRA (r=16, 4-bit base) -> holdout exact-match eval -> save LoRA + GGUF export for
ollama (`ollama create baritone-brain -f outputs/gguf/Modelfile`).
"""
import json
import os
import random

from unsloth import FastLanguageModel  # must be imported before transformers/trl
from datasets import Dataset
from trl import SFTConfig, SFTTrainer

ROOT = os.path.dirname(os.path.abspath(__file__))
DATA_FILES = [os.path.join(ROOT, "data", "synthetic.jsonl"),
              os.path.join(ROOT, "data", "harvested.jsonl")]
OUT_DIR = os.path.join(ROOT, "outputs")
# v5: parametrized so the same script trains candidate sizes, e.g.
#   ./run_train.sh                                                  -> 1.7B, q8_0
#   BRAIN_BASE=unsloth/Qwen3-4B BRAIN_TAG=-4b BRAIN_QUANT=q4_k_m BRAIN_BATCH=1 ./run_train.sh
BASE_MODEL = os.environ.get("BRAIN_BASE", "unsloth/Qwen3-1.7B")
BRAIN_TAG = os.environ.get("BRAIN_TAG", "")
BRAIN_QUANT = os.environ.get("BRAIN_QUANT", "q8_0")
BRAIN_BATCH = int(os.environ.get("BRAIN_BATCH", "2"))
MAX_SEQ = 512
EVAL_FRACTION = 0.05

SYSTEM_PROMPT = (
    "You are baritone-brain, the command brain of a Minecraft Baritone bot. "
    "Convert the player's message into exactly one tool call. "
    "If the request is creative, multi-step, or beyond your tools, call escalate."
)


def load_records():
    records = []
    for path in DATA_FILES:
        if not os.path.exists(path):
            print(f"warning: {path} missing, skipping")
            continue
        for line in open(path, encoding="utf-8"):
            try:
                r = json.loads(line)
                if r.get("goal") and r.get("calls"):
                    records.append(r)
            except json.JSONDecodeError:
                pass
    random.Random(7).shuffle(records)
    return records


# v4 insight: v1-v3 trained on HuggingFace's jinja rendering, but the runtimes render DIFFERENTLY
# (verified byte-exact against ollama 0.30 via prompt_eval_count probes):
#   - ollama /api/chat with think:false appends " /no_think" to the user msg and prefills an empty
#     think block in the assistant prefix
#   - the mod's OpenAI-compat path (/v1/chat/completions) has no think param -> bare assistant
#     prefix, no /no_think; the model must emit the think block itself
# Training on BOTH real renderings removes the format drift that cost v3 ~4 points.

def prompt_bare(goal):
    return ("<|im_start|>system\n\n" + SYSTEM_PROMPT + "<|im_end|>\n"
            "<|im_start|>user\n" + goal + "<|im_end|>\n"
            "<|im_start|>assistant\n")


def prompt_think_false(goal):
    return ("<|im_start|>system\n\n" + SYSTEM_PROMPT + "<|im_end|>\n"
            "<|im_start|>user\n" + goal + " /no_think<|im_end|>\n"
            "<|im_start|>assistant\n<think>\n\n</think>\n\n")


def tool_json(record):
    call = record["calls"][0]
    return json.dumps({"name": call["name"], "arguments": call.get("arguments", {})},
                      ensure_ascii=False)


def training_pairs(record):
    """(prompt, completion) pairs for BOTH runtime renderings. Separate columns let TRL apply
    completion-only loss: gradient flows only through the answer tokens, not the prompt - the
    model's capacity goes entirely into mapping goals to calls (v4 wasted capacity re-learning
    its own system prompt)."""
    call = "<tool_call>\n" + tool_json(record) + "\n</tool_call><|im_end|>\n"
    return [
        (prompt_bare(record["goal"]), "<think>\n\n</think>\n\n" + call),
        (prompt_think_false(record["goal"]), call),
    ]


def main():
    records = load_records()
    n_eval = max(20, int(len(records) * EVAL_FRACTION))
    eval_records, train_records = records[:n_eval], records[n_eval:]
    print(f"{len(train_records)} train / {len(eval_records)} eval examples")

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=BASE_MODEL,
        max_seq_length=MAX_SEQ,
        load_in_4bit=True,
    )
    model = FastLanguageModel.get_peft_model(
        model,
        r=16, lora_alpha=16, lora_dropout=0,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                        "gate_proj", "up_proj", "down_proj"],
        use_gradient_checkpointing="unsloth",
        random_state=7,
    )

    prompts, completions = [], []
    for r in train_records:
        for p, c in training_pairs(r):
            prompts.append(p)
            completions.append(c)
    train_ds = Dataset.from_dict({"prompt": prompts, "completion": completions})

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=train_ds,
        args=SFTConfig(
            max_seq_length=MAX_SEQ,
            completion_only_loss=True,
            per_device_train_batch_size=BRAIN_BATCH,
            gradient_accumulation_steps=8 // BRAIN_BATCH,
            num_train_epochs=4,
            learning_rate=2e-4,
            lr_scheduler_type="cosine",
            warmup_ratio=0.05,
            logging_steps=25,
            optim="adamw_8bit",
            seed=11,
            output_dir=os.path.join(OUT_DIR, "checkpoints"),
            report_to="none",
        ),
    )
    trainer.train()

    # ----- holdout eval: exact tool-name match ------------------------------------------------
    # Eval on BOTH runtime renderings - the game's bare OpenAI-compat format and ollama's
    # /api/chat think:false format - so the in-process score predicts production behavior.
    FastLanguageModel.for_inference(model)
    for label, render in (("game/bare", prompt_bare), ("ollama/think-false", prompt_think_false)):
        correct = 0
        for rec in eval_records:
            inputs = tokenizer(render(rec["goal"]), return_tensors="pt").to(model.device)
            out = model.generate(**inputs, max_new_tokens=120, do_sample=False,
                                 pad_token_id=tokenizer.eos_token_id)
            text = tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)
            expected = rec["calls"][0]["name"]
            if f'"name": "{expected}"' in text or f'"name":"{expected}"' in text:
                correct += 1
        print(f"holdout tool-name accuracy [{label}]: {correct}/{len(eval_records)} "
              f"({100.0 * correct / max(1, len(eval_records)):.1f}%)")

    # ----- save -------------------------------------------------------------------------------
    lora_dir = os.path.join(OUT_DIR, "lora")
    model.save_pretrained(lora_dir)
    tokenizer.save_pretrained(lora_dir)
    print(f"LoRA adapter saved to {lora_dir}")

    # Merge in a FRESH python process with vanilla transformers+peft: unsloth's in-process merge
    # (save_pretrained_merged / save_pretrained_gguf) produced corrupt weights for v3 and v4 -
    # token-salad models that score 0%. The clean peft merge has been correct every time.
    gguf_dir = os.path.join(OUT_DIR, "gguf")
    import subprocess
    import sys as _sys
    merge_script = (
        "import torch\n"
        "from transformers import AutoModelForCausalLM, AutoTokenizer\n"
        "from peft import PeftModel\n"
        f"base = AutoModelForCausalLM.from_pretrained({BASE_MODEL!r}, torch_dtype=torch.bfloat16)\n"
        f"m = PeftModel.from_pretrained(base, {lora_dir!r}).merge_and_unload()\n"
        f"m.save_pretrained({gguf_dir!r})\n"
        f"AutoTokenizer.from_pretrained({lora_dir!r}).save_pretrained({gguf_dir!r})\n"
        "print('clean peft merge done')\n"
    )
    subprocess.run([_sys.executable, "-c", merge_script], check=True)
    converter = os.path.join(ROOT, "llama.cpp", "convert_hf_to_gguf.py")
    gguf_file = os.path.join(OUT_DIR, f"baritone-brain{BRAIN_TAG}-{BRAIN_QUANT}.gguf")
    if os.path.exists(converter):
        subprocess.run([_sys.executable, converter, gguf_dir,
                        "--outfile", gguf_file, "--outtype", BRAIN_QUANT], check=True)
        print(f"GGUF exported to {gguf_file}")
        print("Next: ollama create baritone-brain -f", os.path.join(OUT_DIR, "Modelfile"))
        print("Then in Minecraft: #ollama use baritone-brain")
    else:
        print(f"Merged model saved to {gguf_dir}; clone llama.cpp and run convert_hf_to_gguf.py "
              f"--outfile {gguf_file} --outtype q8_0")


if __name__ == "__main__":
    main()
