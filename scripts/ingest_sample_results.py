#!/usr/bin/env python3
"""Post sample model results to the local ingest API.

Clears existing results (DB + on-disk JSON) by default, then inserts ~30 samples.
The server assigns iterationId (yyyyMMddHHmmss). Do not send it.

Usage (values from the target environment, e.g. .env.dev):
  set -a && source .env.dev && set +a
  export AMRAP_API_BASE=http://localhost:${WEB_PORT}/api
  # Optional override if API talks to host Postgres via host.docker.internal:
  export POSTGRES_HOST=localhost
  python3 scripts/ingest_sample_results.py

  # Keep existing rows (append only):
  python3 scripts/ingest_sample_results.py --keep
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

API_BASE = os.environ.get("AMRAP_API_BASE", "").rstrip("/")
INGEST_KEY = os.environ.get("INGEST_API_KEY", "")
if not API_BASE or not INGEST_KEY:
    sys.exit("Set AMRAP_API_BASE (e.g. http://localhost:5173/api) and INGEST_API_KEY first.")

ROOT = Path(__file__).resolve().parents[1]
RESULTS_DIR = Path(os.environ.get("RESULTS_HOST_DIR", ROOT / "data" / "results"))

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
        "modelName": "CardioNet",
        "version": "1.3.0-rc1",
        "category": "Cardiology",
        "subCategory": "Aortic Valve",
        "confidenceAccuracy": 98.2,
        "sensitivity": 96.0,
        "specificity": 98.8,
        "f1Score": 97.1,
        "recall": 96.0,
        "precision": 98.2,
        "payload": {
            "dataset": "echo-valves-v4",
            "epochs": 55,
            "notes": "Release candidate. Better on calcified valves; slightly slower inference.",
            "classes": ["normal", "stenosis", "regurgitation"],
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
        "modelName": "NeuroSlice",
        "version": "2.1.0",
        "category": "Neurology",
        "subCategory": "Spine",
        "confidenceAccuracy": 93.0,
        "sensitivity": 90.5,
        "specificity": 94.8,
        "f1Score": 91.7,
        "recall": 90.5,
        "precision": 93.0,
        "payload": {
            "dataset": "mri-spine-lumbar",
            "sliceThicknessMm": 2.0,
            "notes": "Lumbar disc herniation detector. Occasional false positives on Modic type I.",
            "classes": ["normal", "herniation", "stenosis"],
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
    {
        "modelName": "ChestScan",
        "version": "1.0.0",
        "category": "Radiology",
        "subCategory": "Lung",
        "confidenceAccuracy": 95.2,
        "sensitivity": 93.4,
        "specificity": 96.1,
        "f1Score": 94.5,
        "recall": 93.4,
        "precision": 95.7,
        "payload": {
            "dataset": "cxr-multi-site",
            "notes": "First production release. Trained on three hospitals; good on ICU portables.",
            "classes": ["clear", "opacity", "effusion", "pneumothorax"],
        },
    },
    {
        "modelName": "RetinaGuard",
        "version": "3.1.0",
        "category": "Ophthalmology",
        "subCategory": "Fundus",
        "confidenceAccuracy": 94.7,
        "sensitivity": 91.8,
        "specificity": 96.2,
        "f1Score": 93.5,
        "recall": 91.8,
        "precision": 95.3,
        "payload": {
            "dataset": "fundus-dr-v2",
            "notes": "Diabetic retinopathy grading. Strong on referable DR; weaker on early NPDR.",
            "classes": ["none", "mild", "moderate", "severe", "proliferative"],
        },
    },
    {
        "modelName": "RetinaGuard",
        "version": "3.0.2",
        "category": "Ophthalmology",
        "subCategory": "Fundus",
        "confidenceAccuracy": 90.1,
        "sensitivity": 86.4,
        "specificity": 92.5,
        "f1Score": 88.2,
        "recall": 86.4,
        "precision": 90.0,
        "payload": {
            "dataset": "fundus-dr-v1",
            "notes": "Previous generation. Higher miss rate on macular edema.",
            "classes": ["none", "mild", "moderate", "severe"],
        },
    },
    {
        "modelName": "RetinaGuard",
        "version": "3.1.1",
        "category": "Ophthalmology",
        "subCategory": "OCT",
        "confidenceAccuracy": 96.8,
        "sensitivity": 95.1,
        "specificity": 97.9,
        "f1Score": 96.0,
        "recall": 95.1,
        "precision": 96.9,
        "payload": {
            "dataset": "oct-macula-2025",
            "notes": "OCT fluid detection. Excellent on intraretinal fluid, rare false alarms on shadowing.",
            "classes": ["dry", "fluid", "scar"],
        },
    },
    {
        "modelName": "LiverSeg",
        "version": "1.0.0",
        "category": "Radiology",
        "subCategory": "Abdomen",
        "confidenceAccuracy": 92.4,
        "sensitivity": 89.0,
        "specificity": 94.5,
        "f1Score": 90.8,
        "recall": 89.0,
        "precision": 92.7,
        "payload": {
            "dataset": "ct-liver-lesions",
            "notes": "CT liver lesion segmentation. Struggles with lesions under 8 mm.",
            "classes": ["parenchyma", "lesion", "vessel"],
        },
    },
    {
        "modelName": "LiverSeg",
        "version": "1.0.1",
        "category": "Radiology",
        "subCategory": "Abdomen",
        "confidenceAccuracy": 94.0,
        "sensitivity": 91.2,
        "specificity": 95.8,
        "f1Score": 92.9,
        "recall": 91.2,
        "precision": 94.6,
        "payload": {
            "dataset": "ct-liver-lesions",
            "epochs": 80,
            "notes": "Patch release. Improved small-lesion recall after hard-example mining.",
            "classes": ["parenchyma", "lesion", "vessel"],
        },
    },
    {
        "modelName": "SkinAid",
        "version": "0.4.0",
        "category": "Dermatology",
        "subCategory": "Lesion",
        "confidenceAccuracy": 88.5,
        "sensitivity": 92.0,
        "specificity": 85.1,
        "f1Score": 89.0,
        "recall": 92.0,
        "precision": 86.2,
        "payload": {
            "dataset": "derm-isic-subset",
            "notes": "Melanoma screening. High sensitivity by design; more false positives on seborrheic keratosis.",
            "classes": ["benign", "melanoma", "bcc"],
        },
    },
    {
        "modelName": "SkinAid",
        "version": "0.4.2",
        "category": "Dermatology",
        "subCategory": "Lesion",
        "confidenceAccuracy": 91.2,
        "sensitivity": 90.5,
        "specificity": 91.8,
        "f1Score": 90.9,
        "recall": 90.5,
        "precision": 91.3,
        "payload": {
            "dataset": "derm-isic-subset",
            "notes": "Balanced threshold. Better specificity than 0.4.0 with a small sensitivity trade-off.",
            "classes": ["benign", "melanoma", "bcc", "scc"],
        },
    },
    {
        "modelName": "BoneAgeNet",
        "version": "2.2.0",
        "category": "Radiology",
        "subCategory": "MSK",
        "confidenceAccuracy": 95.5,
        "sensitivity": 94.0,
        "specificity": 96.5,
        "f1Score": 95.0,
        "recall": 94.0,
        "precision": 96.0,
        "payload": {
            "dataset": "hand-xray-rsna",
            "notes": "Pediatric bone age. MAE about 4.2 months on the validation split.",
            "classes": ["under", "normal", "advanced"],
        },
    },
    {
        "modelName": "BoneAgeNet",
        "version": "2.1.0",
        "category": "Radiology",
        "subCategory": "MSK",
        "confidenceAccuracy": 92.0,
        "sensitivity": 90.0,
        "specificity": 93.5,
        "f1Score": 91.2,
        "recall": 90.0,
        "precision": 92.5,
        "payload": {
            "dataset": "hand-xray-rsna",
            "notes": "Prior release. Larger error on ages under 3 years.",
            "classes": ["under", "normal", "advanced"],
        },
    },
    {
        "modelName": "EchoEF",
        "version": "1.5.0",
        "category": "Cardiology",
        "subCategory": "Ejection Fraction",
        "confidenceAccuracy": 96.0,
        "sensitivity": 93.5,
        "specificity": 97.0,
        "f1Score": 94.8,
        "recall": 93.5,
        "precision": 96.2,
        "payload": {
            "dataset": "echo-ef-apical",
            "notes": "Apical 4-chamber EF estimation. R² 0.89 vs expert biplane.",
            "classes": ["reduced", "midrange", "preserved"],
        },
    },
    {
        "modelName": "EchoEF",
        "version": "1.4.0",
        "category": "Cardiology",
        "subCategory": "Ejection Fraction",
        "confidenceAccuracy": 93.2,
        "sensitivity": 90.0,
        "specificity": 95.0,
        "f1Score": 91.8,
        "recall": 90.0,
        "precision": 93.7,
        "payload": {
            "dataset": "echo-ef-apical",
            "notes": "Weaker on foreshortened views; prefer 1.5.0 when available.",
            "classes": ["reduced", "midrange", "preserved"],
        },
    },
    {
        "modelName": "PathCell",
        "version": "0.8.0",
        "category": "Pathology",
        "subCategory": "Histology",
        "confidenceAccuracy": 90.8,
        "sensitivity": 88.2,
        "specificity": 92.4,
        "f1Score": 89.5,
        "recall": 88.2,
        "precision": 90.9,
        "payload": {
            "dataset": "wsi-breast-tiles",
            "notes": "Tile-level mitosis detection. Edge tiles near artifacts need review.",
            "classes": ["non_mitosis", "mitosis"],
        },
    },
    {
        "modelName": "PathCell",
        "version": "0.8.1",
        "category": "Pathology",
        "subCategory": "Histology",
        "confidenceAccuracy": 92.6,
        "sensitivity": 90.0,
        "specificity": 94.1,
        "f1Score": 91.4,
        "recall": 90.0,
        "precision": 92.8,
        "payload": {
            "dataset": "wsi-breast-tiles",
            "notes": "Color-normalization fix reduced domain shift across scanners.",
            "classes": ["non_mitosis", "mitosis"],
        },
    },
    {
        "modelName": "VoiceTremor",
        "version": "1.0.0",
        "category": "Neurology",
        "subCategory": "Speech",
        "confidenceAccuracy": 87.0,
        "sensitivity": 84.0,
        "specificity": 89.5,
        "f1Score": 85.5,
        "recall": 84.0,
        "precision": 87.1,
        "payload": {
            "dataset": "speech-pd-en",
            "notes": "Parkinson speech screening from sustained phonation. English-only training.",
            "classes": ["control", "parkinson"],
        },
    },
    {
        "modelName": "VoiceTremor",
        "version": "1.0.1",
        "category": "Neurology",
        "subCategory": "Speech",
        "confidenceAccuracy": 89.4,
        "sensitivity": 86.5,
        "specificity": 91.2,
        "f1Score": 88.0,
        "recall": 86.5,
        "precision": 89.6,
        "payload": {
            "dataset": "speech-pd-multi",
            "notes": "Added Hindi and Tamil samples. Still weak on noisy phone recordings.",
            "classes": ["control", "parkinson"],
        },
    },
    {
        "modelName": "KidneySeg",
        "version": "2.0.0",
        "category": "Radiology",
        "subCategory": "Abdomen",
        "confidenceAccuracy": 93.5,
        "sensitivity": 91.0,
        "specificity": 95.0,
        "f1Score": 92.4,
        "recall": 91.0,
        "precision": 93.8,
        "payload": {
            "dataset": "ct-kidney-kits23",
            "notes": "Kidney and tumor segmentation. Occasional bleed into adrenal on thin patients.",
            "classes": ["kidney", "tumor", "cyst"],
        },
    },
    {
        "modelName": "KidneySeg",
        "version": "2.0.1",
        "category": "Radiology",
        "subCategory": "Abdomen",
        "confidenceAccuracy": 94.8,
        "sensitivity": 92.6,
        "specificity": 96.0,
        "f1Score": 93.9,
        "recall": 92.6,
        "precision": 95.2,
        "payload": {
            "dataset": "ct-kidney-kits23",
            "notes": "Boundary refinement pass. Better Dice on cysts under 1 cm.",
            "classes": ["kidney", "tumor", "cyst"],
        },
    },
    {
        "modelName": "MammoNet",
        "version": "4.0.0",
        "category": "Radiology",
        "subCategory": "Breast",
        "confidenceAccuracy": 91.0,
        "sensitivity": 93.5,
        "specificity": 88.8,
        "f1Score": 91.7,
        "recall": 93.5,
        "precision": 90.0,
        "payload": {
            "dataset": "mammo-ddsm-plus",
            "notes": "Screening mammography CAD. Tuned for high recall; BI-RADS 3 cases need radiologist review.",
            "classes": ["benign", "malignant", "normal"],
        },
    },
    {
        "modelName": "MammoNet",
        "version": "4.0.1",
        "category": "Radiology",
        "subCategory": "Breast",
        "confidenceAccuracy": 92.8,
        "sensitivity": 92.0,
        "specificity": 93.2,
        "f1Score": 92.4,
        "recall": 92.0,
        "precision": 92.9,
        "payload": {
            "dataset": "mammo-ddsm-plus",
            "notes": "Recalibrated operating point for lower false positives in dense breasts.",
            "classes": ["benign", "malignant", "normal"],
        },
    },
    {
        "modelName": "ECGBeat",
        "version": "1.2.0",
        "category": "Cardiology",
        "subCategory": "ECG",
        "confidenceAccuracy": 97.0,
        "sensitivity": 95.5,
        "specificity": 97.8,
        "f1Score": 96.4,
        "recall": 95.5,
        "precision": 97.3,
        "payload": {
            "dataset": "ecg-mitbih",
            "notes": "Beat classification on single-lead Holter. AFib episodes longer than 30s are flagged well.",
            "classes": ["N", "S", "V", "F", "Q"],
        },
    },
    {
        "modelName": "ECGBeat",
        "version": "1.1.0",
        "category": "Cardiology",
        "subCategory": "ECG",
        "confidenceAccuracy": 94.1,
        "sensitivity": 91.0,
        "specificity": 95.8,
        "f1Score": 92.8,
        "recall": 91.0,
        "precision": 94.6,
        "payload": {
            "dataset": "ecg-mitbih",
            "notes": "Baseline. More confusion between S and V ectopic beats than 1.2.0.",
            "classes": ["N", "S", "V", "F", "Q"],
        },
    },
]


def clear_existing() -> None:
    """Truncate results tables and remove host JSON files (dev default layout)."""
    host = os.environ.get("POSTGRES_HOST", "localhost")
    if host == "host.docker.internal":
        host = "localhost"
    port = os.environ.get("POSTGRES_PORT", "5432")
    db = os.environ.get("POSTGRES_DB", "AMRAP-Dev")
    user = os.environ.get("POSTGRES_USER", "postgres")
    password = os.environ.get("POSTGRES_PASSWORD", "")
    if not password:
        sys.exit("Set POSTGRES_PASSWORD to clear existing results (or pass --keep).")

    env = os.environ.copy()
    env["PGPASSWORD"] = password
    sql = "TRUNCATE results, result_chunks CASCADE;"
    print(f"Clearing DB {db} on {host}:{port} …")
    subprocess.run(
        ["psql", "-h", host, "-p", port, "-U", user, "-d", db, "-v", "ON_ERROR_STOP=1", "-c", sql],
        check=True,
        env=env,
    )

    if RESULTS_DIR.is_dir():
        removed = 0
        for path in RESULTS_DIR.glob("*.json"):
            path.unlink()
            removed += 1
        print(f"Removed {removed} JSON file(s) under {RESULTS_DIR}")
    else:
        print(f"No host results dir at {RESULTS_DIR} (container volume may still hold files until recreate)")


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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--keep",
        action="store_true",
        help="Do not truncate existing results; append samples only.",
    )
    args = parser.parse_args()

    if not args.keep:
        clear_existing()

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
