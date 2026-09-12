"""
QLoRA fine-tune of a small instruct model on training/data/{train,val}.jsonl
(produced by build_sft_dataset.py) so it reliably emits AsciiActionCodec's
compact ASCII grammar instead of JSON/English.

Sized for an 8GB-class card (RTX 3070): 4-bit base weights + LoRA adapters via
unsloth, default base model Qwen2.5-1.5B-Instruct. Swap --model for a bigger
one once this proves out and VRAM allows.

Usage: python finetune.py [--model Qwen/Qwen2.5-1.5B-Instruct] [--epochs 3] [--out training/out_lora]
"""
import argparse

from unsloth import FastLanguageModel
from datasets import load_dataset
from trl import SFTTrainer, SFTConfig


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="Qwen/Qwen2.5-1.5B-Instruct")
    ap.add_argument("--data-dir", default="training/data")
    ap.add_argument("--out", default="training/out_lora")
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--max-seq-len", type=int, default=512)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--grad-accum", type=int, default=2)
    args = ap.parse_args()

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=args.model,
        max_seq_length=args.max_seq_len,
        load_in_4bit=True,
    )
    model = FastLanguageModel.get_peft_model(
        model,
        r=args.lora_r,
        lora_alpha=args.lora_r,
        lora_dropout=0.0,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        bias="none",
        use_gradient_checkpointing="unsloth",
    )

    def format_example(example):
        return {"text": tokenizer.apply_chat_template(example["messages"], tokenize=False, add_generation_prompt=False)}

    dataset = load_dataset("json", data_files={
        "train": f"{args.data_dir}/train.jsonl",
        "val": f"{args.data_dir}/val.jsonl",
    })
    dataset = dataset.map(format_example, remove_columns=dataset["train"].column_names)

    trainer = SFTTrainer(
        model=model,
        processing_class=tokenizer,
        train_dataset=dataset["train"],
        eval_dataset=dataset["val"],
        args=SFTConfig(
            output_dir=args.out,
            eos_token=tokenizer.eos_token,
            dataset_text_field="text",
            max_length=args.max_seq_len,
            num_train_epochs=args.epochs,
            per_device_train_batch_size=args.batch_size,
            gradient_accumulation_steps=args.grad_accum,
            learning_rate=args.lr,
            warmup_steps=10,
            logging_steps=20,
            eval_strategy="steps",
            eval_steps=100,
            save_strategy="epoch",
            bf16=True,
            optim="adamw_8bit",
            report_to="none",
        ),
    )
    trainer.train()
    model.save_pretrained(args.out)
    tokenizer.save_pretrained(args.out)
    print(f"LoRA adapter saved to {args.out}")


if __name__ == "__main__":
    main()
