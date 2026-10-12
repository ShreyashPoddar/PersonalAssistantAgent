# PAA's own on-device model

**Why:** Gemma 3n E2B (3.1 GB) does the job but takes ~3.5 GB RAM and 40–90 s of all CPU cores per message on the
iQOO Z7 Pro, so the phone hangs. The job is narrow (read a chat message → JSON list of to-dos), and narrow jobs are
where a small fine-tuned model matches a big general one.

**Plan:** distil a big open teacher into Gemma 3 **1B** and **270M**, then ship the smallest that passes the eval.

| | now: Gemma 3n E2B | target: PAA 1B (int8) | target: PAA 270M (int8) |
|---|---|---|---|
| file | 3.1 GB | ~1 GB | ~300 MB |
| RAM while running | ~3.5 GB | ~1.2 GB | ~0.5 GB |
| runs on | CPU (too big for GPU) | phone GPU | phone GPU |
| prompt | ~750 tokens (rules + examples) | ~150 tokens (rules learned) | ~150 tokens |
| expected time/message | 40–90 s | a few s | ~1–2 s |

(Targets are estimates until measured on the phone.)

## Pipeline (each step is one queue job on the DGX, see `scripts/dgx/README.md`)

1. `gen_data.py` — the teacher (Qwen3-32B-AWQ on one A100) **invents** ~20k WhatsApp situations in Hinglish /
   Hindi / English covering every case the app handles (promises, requests, group mentions, bursts, calls, ads,
   batch-specific internships, done/update of open tasks…), then labels them with the app's own rules. A label is
   kept only if 3 teacher samples agree and no name in a title is invented. **No real chat is used, ever.**
2. `train_sft.py` — full fine-tune of `google/gemma-3-1b-it` / `-270m-it`, loss on the answer only. The prompt is
   `paa_format.py`, byte-identical to the app's `LocalLlm.buildCompactPrompt` (`CompactPromptTest` enforces it).
3. `eval_model.py` — held-out synthetic test set (task found / not, title F1, deadline match, done/update) + the
   offline version of `tools/scenarios.json`.
4. `export_task.py` — ai-edge-torch → int8 `.tflite` → MediaPipe bundle `paa-tasks-<size>-<tag>.task`.
5. Phone: `python tools/gpu.py fetch export-1b-v1`, copy the `.task` to the phone and pick it in PAA's model
   installer (the `paa-` name switches the app to the compact prompt), or with adb:
   ```bash
   adb push paa-tasks-1b-v1.task /sdcard/Android/data/com.paa.assistant/files/models/local_model.task
   adb shell touch /sdcard/Android/data/com.paa.assistant/files/models/local_model.task.paa
   ```
   Then the real gate: `python tools/eval_chats.py --serial emulator-5554` must stay at 15/15, and time/RAM on the phone.

## Quick checks without a GPU
```bash
python train/paa_format.py --golden     # after changing the prompt on either side, then run the unit tests
```
