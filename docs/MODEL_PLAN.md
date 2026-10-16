# PAA on-device model plan

Goal: task extraction from chats that is **fast, accurate and light on the phone** (iQOO Z7 Pro, Dimensity 7200,
8 GB): no hangs, low CPU/RAM/battery, chat content never leaves the phone.
Pipeline and GPU lane: `train/README.md`, `scripts/dgx/README.md`. Gains below are estimates until measured on the phone.

## Status (2026-10-16)

| | |
|---|---|
| Data v1 | Done: 6,018 kept examples (train 5,672 / val 180 / test 166; 3,861 with tasks). 20,000 planned; ~11k lost to the restore bug |
| Training v1 | `chain-v1` queued: train 1B → eval → train 270M → eval → hold the card; exports run on CPU as each model is saved |
| DGX queue | Hung 3 days (network drop + `git fetch` with no time limit). Fixed in `2d41670`, waiting for `git pull` + restart on campus |
| Fixed bugs | Crash with an empty artifacts dir; restore-from-HF over a running job's output; no time limits on network calls; card lost between jobs (now chained and claimed at start) |
| App | Compact prompt for `paa-*.task` models, parity-tested against training (`CompactPromptTest`) |

## Where the time and battery go now

Gemma 3n E2B, 3.1 GB, CPU only: ~3.5 GB RAM, 40–90 s of all cores per message, ~750-token prompt.
Cost = **prompt reading** (tokens in) + **answer writing** (tokens out) + **how many messages reach the model**.
The plan attacks all three.

## Roadmap

### Phase 1: v1 model on the phone (in progress)
1. Finish `chain-v1` (1B + 270M), exports `paa-tasks-1b-v1.task`, `paa-tasks-270m-v1.task`.
2. On the phone: time per message, RAM, heat; `tools/eval_chats.py` must stay 15/15.
3. Ship the smallest model that passes.

### Phase 2: stop the phone from hanging, whatever model it runs
| # | Idea | How | Gain | Effort |
|---|---|---|---|---|
| 2.1 | **Learned gate** (LocalAI's hashed n-gram memory, applied) | Hashed char/word n-grams → logistic regression, ~1–3 MB, plain Kotlin math, <5 ms. Trained on the synthetic data; replaces the hand-written `TaskSignal` | ~70–80% of messages never wake the model | Low |
| 2.2 | **Exact-match cache** | Normalised message (+ chat type) → last verdict | Repeats cost nothing | Very low |
| 2.3 | **Run it at low priority** | Low-priority thread, GPU backend, back off when hot or the UI is busy | Smooth UI | Low |
| 2.4 | **Batch until idle or charging** | Personal/urgent chats now; groups and promos wait for screen-off or charging and go in one batch | Big CPU/battery cut | Low |
| 2.5 | **Contact priority** | Important contacts (family, project groups) processed first; the rest batched | Faster where it matters | Low |
| 2.6 | **Separate process for the model** | Run inference in its own process, so under memory pressure Android kills that, not the UI or listeners | No app-wide freezes | Medium |
| 2.7 | **No double processing** | One message seen by notification + accessibility + burst = one inference (verify dedupe) | Fewer runs | Low |
| 2.8 | **Fewer CPU threads** | If on CPU: 2 threads on efficiency cores for background work | Less heat | Very low |

### Phase 3: v2 model, cheaper answers and better accuracy
| # | Idea | How | Gain | Effort |
|---|---|---|---|---|
| 3.1 | **One-word "no task"** | Model writes `N` and stops when there is no task | Most messages: ~1 output token instead of ~60 | Low (retrain + parser) |
| 3.2 | **Shorter answer format** | Drop `reason`, short keys, dates as copied words | ~2× fewer output tokens | Low |
| 3.3 | **Pre-filled answer start** | App writes `{"t":[`, the model continues | Fewer tokens, no format drift | Very low |
| 3.4 | **Stop when the answer is complete** | Cut generation when brackets balance | Saves trailing tokens | Very low |
| 3.5 | **Context only when needed** | Earlier lines only if the message refers back ("it", "wo", "usko", "same") or is a reply | Shorter prompts | Low |
| 3.6 | **More and better data** | 20k+ examples, hard cases (bursts, done/update, batch eligibility), counterfactual pairs (minimal edits that flip task/no-task), class balance | Accuracy | Low (one DGX job) |
| 3.7 | **Learn the teacher's confidence** | Train on the teacher's top-k probabilities, not only its answer | Better accuracy and calibrated confidence | Medium |
| 3.8 | **Calibrated thresholds** | Fit confidence → auto-schedule / ask / ignore cut-offs on the validation set | Fewer wrong alarms and asks | Low |
| 3.9 | **Smaller memory per run** | KV cache 1280 → ~768 once prompts are short | Less RAM | Very low |

### Phase 4: smaller and faster model
| # | Idea | How | Gain | Effort |
|---|---|---|---|---|
| 4.1 | **Vocabulary trimming** | 270M has ~170M params in its 262k-word vocabulary; keep the ~40k used by English/Hinglish/Hindi | ~½ size and RAM | Medium |
| 4.2 | **Layer pruning** | Drop ~30% of 1B layers, short retrain to heal | ~1.4× faster | Medium |
| 4.3 | **Train for 4-bit** (QAT) | 1B at int4 with near-int8 accuracy | ~0.6 GB file | Medium |
| 4.4 | **Cascade** (LocalAI's mixture of experts, applied) | Gate → 270M → 1B only when unsure | Speed of 270M, accuracy of 1B | Medium |
| 4.5 | **Phone NPU** | Dimensity 7200's MediaTek APU through LiteRT's NPU support | Possibly much lower battery/heat | Experiment |

### Phase 5: one model for everything, personal and private
| # | Idea | How | Gain | Effort |
|---|---|---|---|---|
| 5.1 | **Add-ons for each job** (LoRA) | One base model + few-MB adapters: chat tasks, offline voice commands, call transcripts | One model in RAM; offline voice without Gemini | Medium |
| 5.2 | **Personal memory** (LocalAI's inspectable/deletable memory, applied) | Your ✓/✗ judgements, contacts, recurring tasks in an on-phone store; the most similar ones go into the prompt; "forget" deletes for real | Learns your habits, no retraining | Medium |
| 5.3 | **Small updates** | Ship new adapters (MBs) instead of whole models | Fast, cheap updates | Low once 5.1 exists |
| 5.4 | **Shadow testing on the phone** | New model runs silently beside the current one for a week; the app shows agreement stats locally | Safe upgrades with real behaviour, nothing leaves the phone | Low |
| 5.5 | **Flag doubts** | Phone lists low-confidence cases; you may type a made-up look-alike in PAA for the next data round | Targeted improvement, privacy kept | Low |
| 5.6 | **Benchmark on the phone** | Time, RAM, battery for each message in the 🧠 card (extends S0 stats) | Decisions on real numbers | Low |

### Not doing (and why)
- **LocalAI's own architecture on the phone:** 7B weights in memory, not supported by MediaPipe, little Hindi pretraining.
- **JEPA:** a pretraining research method, no practical phone gain.
- **Speculative decoding / grammar-constrained decoding:** not supported by MediaPipe today.
- **Real chats for training:** chat content never leaves the phone; only synthetic or user-typed examples.
- **Gemini Nano / AICore:** not available on this phone.

## Decision rule
Each phase is judged on the phone: time per message, peak RAM, battery per 100 messages, and eval (15/15 scenarios +
synthetic test F1). A change ships only if it keeps eval and improves at least one of the others.
