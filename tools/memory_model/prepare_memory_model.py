#!/usr/bin/env python3
"""
Reproducible preparation script for LICHI AI Memory OS V6 Semantic Encoder.
Model: MongoDB/mdbr-leaf-ir
Revision: b5fe372a656566aca5eae359f0b1d880e48610b1
Dimensions: 256 (MRL - Matryoshka Representation Learning)
Format: Optimized INT8 ONNX
Target: app/src/main/assets/memory_model/
"""

import os
import sys
import json
import hashlib
import shutil
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from transformers import AutoTokenizer, AutoModel, AutoConfig
import onnx
import onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType

MODEL_ID = "MongoDB/mdbr-leaf-ir"
REVISION = "b5fe372a656566aca5eae359f0b1d880e48610b1"
ASSETS_DIR = "app/src/main/assets/memory_model"
TEMP_DIR = "/tmp/memory_model_export"
TARGET_DIM = 256
MAX_SEQ_LENGTH = 512

class EndToEndMemoryEncoder(nn.Module):
    def __init__(self, base_model, target_dim=256):
        super().__init__()
        self.base_model = base_model
        self.target_dim = target_dim

    def forward(self, input_ids, attention_mask, token_type_ids=None):
        if token_type_ids is not None:
            outputs = self.base_model(
                input_ids=input_ids,
                attention_mask=attention_mask,
                token_type_ids=token_type_ids
            )
        else:
            outputs = self.base_model(
                input_ids=input_ids,
                attention_mask=attention_mask
            )
        
        token_embeddings = outputs.last_hidden_state  # [batch_size, seq_len, hidden_dim]
        
        # Mean Pooling with attention_mask
        input_mask_expanded = attention_mask.unsqueeze(-1).expand(token_embeddings.size()).float()
        sum_embeddings = torch.sum(token_embeddings * input_mask_expanded, 1)
        sum_mask = torch.clamp(input_mask_expanded.sum(1), min=1e-9)
        pooled = sum_embeddings / sum_mask
            
        # L2 normalize full embedding
        normed = F.normalize(pooled, p=2, dim=1)
        
        # Matryoshka Representation Learning (MRL): Truncate to first target_dim dimensions
        truncated = normed[:, :self.target_dim]
        
        # L2 normalize truncated 256-d embedding
        final_embedding = F.normalize(truncated, p=2, dim=1)
        return final_embedding

def calculate_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()

def main():
    print(f"=== Preparing Memory OS V6 Semantic Encoder ({MODEL_ID} @ {REVISION}) ===")
    os.makedirs(TEMP_DIR, exist_ok=True)
    os.makedirs(ASSETS_DIR, exist_ok=True)

    print("1. Downloading tokenizer and model configuration...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, revision=REVISION)
    config = AutoConfig.from_pretrained(MODEL_ID, revision=REVISION)
    base_model = AutoModel.from_pretrained(MODEL_ID, revision=REVISION)
    base_model.eval()

    encoder = EndToEndMemoryEncoder(base_model, target_dim=TARGET_DIM)
    encoder.eval()

    print("2. Exporting PyTorch model to ONNX using TorchScript tracing (complete weights)...")
    fp32_onnx_path = os.path.join(TEMP_DIR, "memory_encoder_fp32.onnx")
    dummy_input_ids = torch.tensor([[101, 2023, 2003, 1037, 3231, 102]], dtype=torch.long)
    dummy_mask = torch.ones_like(dummy_input_ids)
    dummy_token_type_ids = torch.zeros_like(dummy_input_ids)

    torch.onnx.export(
        encoder,
        (dummy_input_ids, dummy_mask, dummy_token_type_ids),
        fp32_onnx_path,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["sentence_embedding"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "sequence_length"},
            "attention_mask": {0: "batch_size", 1: "sequence_length"},
            "token_type_ids": {0: "batch_size", 1: "sequence_length"},
            "sentence_embedding": {0: "batch_size"}
        },
        dynamo=False
    )
    print(f"Exported FP32 ONNX model: {os.path.getsize(fp32_onnx_path) / (1024*1024):.2f} MB")

    print("3. Validating FP32 ONNX model against PyTorch reference before quantization...")
    fp32_ort_session = ort.InferenceSession(fp32_onnx_path, providers=["CPUExecutionProvider"])
    
    test_sentences = [
        "Represent this sentence for searching relevant passages: mera naam kya hai?",
        "Represent this sentence for searching relevant passages: main abhi Noida mein rehta hoon",
        "Represent this sentence for searching relevant passages: Durga Puja dates 2026",
        "Represent this sentence for searching relevant passages: fix browser navigation generation id",
        "Represent this sentence for searching relevant passages: what is my preferred programming language?",
        "Represent this sentence for searching relevant passages: user shifted from Delhi to Noida",
        "Represent this sentence for searching relevant passages: LICHI AI memory operating system architecture",
        "Represent this sentence for searching relevant passages: terminal command execution verification"
    ]

    fp32_similarities = []
    for sentence in test_sentences:
        inputs = tokenizer(sentence, return_tensors="pt", max_length=MAX_SEQ_LENGTH, truncation=True)
        with torch.no_grad():
            token_types = inputs.get("token_type_ids", torch.zeros_like(inputs["input_ids"]))
            ref_emb = encoder(inputs["input_ids"], inputs["attention_mask"], token_types).numpy()[0]
        
        ort_inputs = {
            "input_ids": inputs["input_ids"].numpy().astype(np.int64),
            "attention_mask": inputs["attention_mask"].numpy().astype(np.int64),
            "token_type_ids": token_types.numpy().astype(np.int64)
        }
        ort_outs = fp32_ort_session.run(None, ort_inputs)
        fp32_emb = ort_outs[0][0]

        cos_sim = float(np.dot(ref_emb, fp32_emb) / (np.linalg.norm(ref_emb) * np.linalg.norm(fp32_emb)))
        fp32_similarities.append(cos_sim)
        print(f"FP32 sample: '{sentence[:45]}...' -> Cosine: {cos_sim:.6f}")

    mean_fp32_cos = float(np.mean(fp32_similarities))
    print(f"Mean FP32 Cosine Similarity before quantization: {mean_fp32_cos:.6f}")
    if mean_fp32_cos < 0.98:
        print(f"ERROR: FP32 Validation failed! Mean cosine similarity {mean_fp32_cos:.6f} < 0.98")
        sys.exit(1)
    print("SUCCESS: Stage 1 (FP32 ONNX vs Reference) validated (>= 0.98 threshold).")

    print("4. Quantizing ONNX model to INT8 (dynamic quantization)...")
    int8_onnx_path = os.path.join(TEMP_DIR, "memory_encoder_int8.onnx")
    quantize_dynamic(
        model_input=fp32_onnx_path,
        model_output=int8_onnx_path,
        weight_type=QuantType.QInt8,
        per_channel=True
    )
    print(f"Quantized INT8 ONNX model: {os.path.getsize(int8_onnx_path) / (1024*1024):.2f} MB")

    print("5. Validating INT8 ONNX model...")
    int8_ort_session = ort.InferenceSession(int8_onnx_path, providers=["CPUExecutionProvider"])
    int8_similarities = []
    for sentence in test_sentences:
        inputs = tokenizer(sentence, return_tensors="pt", max_length=MAX_SEQ_LENGTH, truncation=True)
        with torch.no_grad():
            token_types = inputs.get("token_type_ids", torch.zeros_like(inputs["input_ids"]))
            ref_emb = encoder(inputs["input_ids"], inputs["attention_mask"], token_types).numpy()[0]
        
        ort_inputs = {
            "input_ids": inputs["input_ids"].numpy().astype(np.int64),
            "attention_mask": inputs["attention_mask"].numpy().astype(np.int64),
            "token_type_ids": token_types.numpy().astype(np.int64)
        }
        ort_outs = int8_ort_session.run(None, ort_inputs)
        int8_emb = ort_outs[0][0]

        cos_sim = float(np.dot(ref_emb, int8_emb) / (np.linalg.norm(ref_emb) * np.linalg.norm(int8_emb)))
        int8_similarities.append(cos_sim)
        print(f"INT8 sample: '{sentence[:45]}...' -> Cosine: {cos_sim:.5f}")

    mean_int8_cos = float(np.mean(int8_similarities))
    print(f"Mean INT8 Cosine Similarity: {mean_int8_cos:.5f}")

    print("6. Copying bundled runtime assets to app/src/main/assets/memory_model/...")
    target_onnx = os.path.join(ASSETS_DIR, "memory_encoder_int8.onnx")
    shutil.copyfile(int8_onnx_path, target_onnx)
    
    # Save tokenizer files
    tokenizer.save_pretrained(ASSETS_DIR)
    
    # Clean unnecessary tokenizer files, keep only required ones
    required_files = {"memory_encoder_int8.onnx", "vocab.txt", "tokenizer_config.json", "special_tokens_map.json", "tokenizer.json"}
    for f in os.listdir(ASSETS_DIR):
        if f not in required_files and not f.endswith(".json") and not f.endswith(".txt") and not f.endswith(".onnx"):
            file_path = os.path.join(ASSETS_DIR, f)
            if os.path.isfile(file_path):
                os.remove(file_path)

    sha256_hash = calculate_sha256(target_onnx)
    print(f"ONNX Model SHA-256: {sha256_hash}")

    metadata = {
        "modelId": MODEL_ID,
        "revision": REVISION,
        "license": "Apache-2.0",
        "modelVersion": "memory-leaf-ir-v2-int8-256",
        "embeddingDimensions": 768,
        "storedDimensions": TARGET_DIM,
        "quantizationType": "INT8_DYNAMIC",
        "quantizationRange": [-0.3, 0.3],
        "maxSequenceLength": MAX_SEQ_LENGTH,
        "sha256": sha256_hash,
        "conversionToolVersion": "onnxruntime-1.30.0",
        "conversionTimestamp": 1790924735000,
        "queryPrefix": "Represent this sentence for searching relevant passages: ",
        "distanceMetric": "COSINE_SIMILARITY",
        "hnswParams": {
            "M": 12,
            "efConstruction": 64,
            "efSearch": 32,
            "topK": 32
        }
    }

    meta_path = os.path.join(ASSETS_DIR, "memory_model_info.json")
    with open(meta_path, "w") as f:
        json.dump(metadata, f, indent=2)

    print(f"Model assets successfully prepared in {ASSETS_DIR}")
    print("Files:")
    for f in os.listdir(ASSETS_DIR):
        sz = os.path.getsize(os.path.join(ASSETS_DIR, f))
        print(f"  - {f}: {sz} bytes ({sz / (1024*1024):.2f} MB)")

if __name__ == "__main__":
    main()
