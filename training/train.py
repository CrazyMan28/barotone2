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
BASE_MODEL = "unsloth/Qwen3-1.7B"
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


def to_messages(record):
    # Train on the first call of each mission: the router's job is "goal -> first correct action".
    call = record["calls"][0]
    tool_json = json.dumps({"name": call["name"], "arguments": call.get("arguments", {})},
                           ensure_ascii=False)
    assistant = "<think>\n\n</think>\n<tool_call>\n" + tool_json + "\n</tool_call>"
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": record["goal"]},
        {"role": "assistant", "content": assistant},
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

    def fmt(rec):
        return tokenizer.apply_chat_template(to_messages(rec), tokenize=False,
                                             add_generation_prompt=False)

    train_ds = Dataset.from_dict({"text": [fmt(r) for r in train_records]})

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=train_ds,
        args=SFTConfig(
            dataset_text_field="text",
            max_seq_length=MAX_SEQ,
            per_device_train_batch_size=2,
            gradient_accumulation_steps=4,
            num_train_epochs=3,
            learning_rate=2e-4,
            lr_scheduler_type="cosine",
            warmup_ratio=0.05,
            logging_steps=25,
            optim="adamw_8bit",
            seed=7,
            output_dir=os.path.join(OUT_DIR, "checkpoints"),
            report_to="none",
        ),
    )
    trainer.train()

    # ----- holdout eval: exact tool-name match ------------------------------------------------
    FastLanguageModel.for_inference(model)
    correct = 0
    for rec in eval_records:
        msgs = to_messages(rec)[:2]  # system + user
        prompt = tokenizer.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True,
                                               enable_thinking=False)
        inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
        out = model.generate(**inputs, max_new_tokens=120, do_sample=False,
                             pad_token_id=tokenizer.eos_token_id)
        text = tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)
        expected = rec["calls"][0]["name"]
        if f'"name": "{expected}"' in text or f'"name":"{expected}"' in text:
            correct += 1
    print(f"holdout tool-name accuracy: {correct}/{len(eval_records)} "
          f"({100.0 * correct / max(1, len(eval_records)):.1f}%)")

    # ----- save -------------------------------------------------------------------------------
    lora_dir = os.path.join(OUT_DIR, "lora")
    model.save_pretrained(lora_dir)
    tokenizer.save_pretrained(lora_dir)
    print(f"LoRA adapter saved to {lora_dir}")

    gguf_dir = os.path.join(OUT_DIR, "gguf")
    model.save_pretrained_gguf(gguf_dir, tokenizer, quantization_method="q4_k_m")
    print(f"GGUF exported to {gguf_dir}")
    print("Next: ollama create baritone-brain -f", os.path.join(gguf_dir, "Modelfile"))
    print("Then in Minecraft: #ollama use baritone-brain")


if __name__ == "__main__":
    main()
