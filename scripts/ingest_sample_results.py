#!/usr/bin/env python3
"""Post sample model results to the local ingest API.

The server assigns iterationId (yyyyMMddHHmmss). Do not send it.

Usage:
  export INGEST_API_KEY=change-me-ingest-key   # match the API's INGEST_API_KEY
  python3 scripts/ingest_sample_results.py

Optional:
  AMRAP_API_BASE=http://localhost:8080/api python3 scripts/ingest_sample_results.py
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

API_BASE = os.environ.get("AMRAP_API_BASE", "http://localhost:8080/api").rstrip("/")
INGEST_KEY = os.environ.get("INGEST_API_KEY", "change-me-ingest-key")

# Filter columns live next to payload. Metrics are percentage points, 0 to 100.
SAMPLES = [
    {
        "modelName": "CardioNet",
        "version": "1.2.0",
        "category": "Cardiology",
        "subCategory": "Aortic Valve",
        "confidenceAccuracy": 97.5,
        "sensitivity": 94.2,
        "specificity": 98.1,
        "f1Score": 95.8,
        "recall": 94.2,
        "precision": 97.3,
        "payload": {
            "dataset": "echo-valves-v3",
            "epochs": 40,
            "notes": "Hold-out set of 1,200 studies. Low false-positive rate on bicuspid valves.",
            "classes": ["normal", "stenosis", "regurgitation"],
        },
    },
    {
        "modelName": "CardioNet",
        "version": "1.1.0",
        "category": "Cardiology",
        "subCategory": "Mitral Valve",
        "confidenceAccuracy": 91.4,
        "sensitivity": 88.0,
        "specificity": 93.6,
        "f1Score": 89.7,
        "recall": 88.0,
        "precision": 91.5,
        "payload": {
            "dataset": "echo-valves-v3",
            "epochs": 28,
            "notes": "Earlier checkpoint. Misses mild prolapse more often than 1.2.0.",
            "classes": ["normal", "prolapse", "regurgitation"],
        },
    },
    {
        "modelName": "NeuroSlice",
        "version": "2.0.1",
        "category": "Neurology",
        "subCategory": "Brain",
        "confidenceAccuracy": 96.1,
        "sensitivity": 92.8,
        "specificity": 97.4,
        "f1Score": 94.0,
        "recall": 92.8,
        "precision": 95.2,
        "payload": {
            "dataset": "mri-brain-2025",
            "sliceThicknessMm": 1.0,
            "notes": "T1 and FLAIR. Strong on white-matter lesions, weaker on small metastases.",
            "classes": ["healthy", "lesion", "tumor"],
        },
    },
    {
        "modelName": "NeuroSlice",
        "version": "2.0.0",
        "category": "Neurology",
        "subCategory": "Brain",
        "confidenceAccuracy": 89.3,
        "sensitivity": 85.5,
        "specificity": 91.0,
        "f1Score": 86.4,
        "recall": 85.5,
        "precision": 87.4,
        "payload": {
            "dataset": "mri-brain-2024",
            "sliceThicknessMm": 3.0,
            "notes": "Thicker slices. Use only as a baseline against 2.0.1.",
            "classes": ["healthy", "lesion"],
        },
    },
    {
        "modelName": "ChestScan",
        "version": "0.9.4",
        "category": "Radiology",
        "subCategory": "Lung",
        "confidenceAccuracy": 93.8,
        "sensitivity": 90.1,
        "specificity": 95.6,
        "f1Score": 92.2,
        "recall": 90.1,
        "precision": 94.4,
        "payload": {
            "dataset": "cxr-pa-lat",
            "notes": "PA and lateral views. Opacity detection is the primary label.",
            "classes": ["clear", "opacity", "effusion"],
        },
    },
    {
        "modelName": "ChestScan",
        "version": "0.9.3",
        "category": "Radiology",
        "subCategory": "Lung",
        "confidenceAccuracy": 84.6,
        "sensitivity": 79.2,
        "specificity": 88.4,
        "f1Score": 81.5,
        "recall": 79.2,
        "precision": 84.0,
        "payload": {
            "dataset": "cxr-portable",
            "notes": "Portable films only. Higher noise, lower sensitivity than 0.9.4.",
            "classes": ["clear", "opacity"],
        },
    },
]


def post_result(body: dict) -> tuple[int, str]:
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        f"{API_BASE}/results",
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Ingest-Key": INGEST_KEY,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8")


def main() -> int:
    print(f"POST {API_BASE}/results  ({len(SAMPLES)} samples)")
    failures = 0
    for sample in SAMPLES:
        status, raw = post_result(sample)
        label = f"{sample['modelName']} {sample['version']} {sample['category']} / {sample['subCategory']}"
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        if status == 201:
            print(f"  201  {label}  iterationId={parsed.get('iterationId')}")
        else:
            failures += 1
            message = parsed.get("message", raw)
            print(f"  {status}  {label}  {message}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
