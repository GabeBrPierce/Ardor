"""
Merges a LoRA adapter into the base model and exports it to GGUF for serving
via llama.cpp / llama-swap / Ollama -- the format VoicePipeline's
"single_command" ResponseHandler mode needs (see TODO.md) to point llmBaseUrl
at a real local server instead of a cloud API.

Usage: python export_gguf.py [--adapter training/out_lora_3b] [--out training/gguf] [--quant q4_k_m]
"""
import argparse

from unsloth import FastLanguageModel


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adapter", default="training/out_lora_3b")
    ap.add_argument("--out", default="training/gguf")
    ap.add_argument("--quant", default="q4_k_m")
    args = ap.parse_args()

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=args.adapter, max_seq_length=512, load_in_4bit=True
    )
    model.save_pretrained_gguf(args.out, tokenizer, quantization_method=args.quant)
    print(f"GGUF export -> {args.out}")


if __name__ == "__main__":
    main()
