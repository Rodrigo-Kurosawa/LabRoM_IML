# LabRoM_IML — Optimization & Productisation Roadmap

> Authoritative list of next-step priorities for the sex-classifier plugin.
> Snapshot date: 2026-05-12. Update this file as items are completed.

---

## 1. Current performance baseline

All numbers below are pivot-stage gaps for a 73-image SC series, measured on
the developer machine. The pivot stage was the original bottleneck; classify
(YOLO + Grad-CAM × 11) is now the dominant remaining cost.

| State of the code | First click of session | Warm click |
|---|---|---|
| Original (per-image, cold each click) | ~27 s | ~27 s |
| Step 1 — persistent worker | ~18 s | ~16 s (projected) |
| Step 2 — batched ResNet forward (`PIVOT_BATCH_SIZE=16`) | 18.5 s | **7.4 s** |
| Step 3 — `ThreadPoolExecutor` for PIL preprocess (Path B) | (combined with stride-2) | (combined with stride-2) |
| Step 3.5 — stride-2 pivot scan (`PIVOT_STRIDE=2`) | 14.1 s | **3.3 s** |
| Step 4 — pre-warm worker on plugin activation | **3.4 s** | 3.3 s |

**Net result: ~8× faster on every click vs. the original baseline.** First-click
freeze is gone. Pivot is no longer the bottleneck; **Grad-CAM × 11 (~4–6 s) is**.

---

## 2. Hard constraints (do not violate)

1. **Grad-CAM heatmaps are a product requirement.** Forensic-anthropology
   defensibility requires the actual Grad-CAM algorithm. Eigen-CAM /
   Score-CAM / Ablation-CAM substitutes are different scientific objects and
   are not acceptable.
2. **Pure-ONNX deployment is therefore off the table.** ONNX Runtime is
   inference-only; Grad-CAM needs PyTorch autograd. A hybrid (ONNX for
   pivot, PyTorch for Grad-CAM) was considered and rejected — the MSI-size
   win disappears because torch must stay, and the remaining runtime win
   (~3 s/click) is not worth the permanent two-runtime complexity.
3. **Windows MSI must install on any Windows 10+ machine** with no
   pre-installed Python, Java, CUDA, or developer tools.
4. **Forensic data is sensitive.** No automatic telemetry. No phoning home.
   Even crash dumps must be local-only and user-initiated to upload.

---

## 3. Prioritised next steps

Ranked by leverage (impact ÷ cost). Each item has one line on **what**, one on
**why now**, one on **risk**.

### 3.1 Worker resilience: auto-respawn (× 1) + surface failures to UI
- **What:** In `PythonWorker.ensureStarted()`, allow exactly one respawn after
  `dead == true`. Add a `PipelineResult.error("AI service unavailable")` path
  surfaced via `JOptionPane`/the existing result panel — not just SLF4J logs.
- **Why now:** Today a single Python crash poisons the whole Weasis session.
  Every subsequent click silently fails until a Weasis restart. Cheap fix
  (one counter, one dialog), prevents a guaranteed support ticket.
- **Risk:** Auto-respawn could mask a real bug if unbounded. Single-retry
  policy avoids that.

### 3.2 Code-sign the MSI
- **What:** Buy an OV code-signing certificate (~$200–400/yr). Run `signtool`
  on the produced MSI inside `distribute.ps1`. EV cert (~$500–800/yr) for
  immediate SmartScreen reputation if budget allows.
- **Why now:** Unsigned MSI ⇒ Windows SmartScreen full-screen warning on
  install. Most hospital/forensic IT departments will refuse to install
  unsigned executables. This is the single line item that turns "research
  artefact" into "deployable product."
- **Risk:** Ongoing annual cost. Cert reissuance pain. Cost of being a
  Windows ISV.

### 3.3 Progress bar with per-image ETA
- **What:** Replace `SexClassifierTool.pushStatus("…")` text updates with a
  determinate `JProgressBar`. Drive it from the `IMG:` / `PROB:` lines the
  worker already emits — `PythonWorker` needs a per-call
  `Consumer<int>` progress callback.
- **Why now:** "Loading models…" with no progress is anxiety-inducing.
  Bar moving 0/73 → 73/73 turns "is it stuck?" into "almost done." ~80 lines
  of Java + a small protocol-callback surface.
- **Risk:** Stalls inside a batched forward (no `PROB:` emitted during the
  compute window) will still feel like a freeze. Acceptable.

### 3.4 Result export (PDF report or PNG bundle)
- **What:** A "Save Report" button on `SexClassifierTool` that exports:
  composite images + final classification + per-image confidence + Patient
  ID + timestamp + model version, into a stamped PDF or a zip of PNGs.
  Add `org.apache.pdfbox:pdfbox` to `pom.xml`.
- **Why now:** Every classification is currently ephemeral. A forensic
  anthropologist cannot keep results; a case that goes to court has no
  paper trail. This is the difference between a tool you use and a tool you
  can defend in a deposition.
- **Risk:** PDFBox adds ~10 MB to the MSI. Worth it.

### 3.5 Manual pivot override + confidence floor
- **What:** When the pivot probability of the chosen frame is below e.g. 0.3,
  display the picked frame with a "Use a different frame?" affordance
  (frame selector). Always allow the user to override even on high
  confidence.
- **Why now:** Forensic anthropologists are domain experts who will not
  accept "the AI said so." Letting them correct the pivot raises clinical
  trust *and* gives a free annotation loop for future retraining.
- **Risk:** Once override is allowed, classification must be re-runnable on
  the new window — small additional UI work.

### 3.6 CPU-only torch wheels in distribute.ps1
- **What:** Pin the torch install in `distribute.ps1` to
  `--index-url https://download.pytorch.org/whl/cpu`.
- **Why now:** Today's wheel likely carries ~1.5 GB of CUDA + cuDNN DLLs
  that never run. Cuts the bundled Python runtime by roughly half. Smaller
  MSI installs faster, downloads faster, is less scary on a hospital
  network.
- **Risk:** None for this CPU-only deployment.

### 3.7 Local crash/exception reporting (no auto-upload)
- **What:** Catch uncaught exceptions in `SexClassifierAction.runPipeline`
  to `%LOCALAPPDATA%\LabRoM\crashes\` with stack trace, plugin version,
  Weasis version. Add a "Send report" button that the user clicks
  intentionally.
- **Why now:** Today there is zero visibility into field failures. Local
  dumps + manual upload preserve privacy while giving real diagnostic
  signal when users report bugs.
- **Risk:** Crash logs can capture file paths containing case IDs / patient
  names. Must sanitise before display or upload.

### 3.8 Pre-warm models also when no DICOM viewer is open yet
- **What:** Currently pre-warm fires from `SexClassifierToolBarFactory`
  which only activates when a 2-D DICOM container exists. If the user
  opens a DICOM in a different viewer first, pre-warm hasn't run.
  Consider triggering from a higher-level activator.
- **Why now:** Edge case; lower priority than the items above. Worth doing
  only after they're done.
- **Risk:** Wastes RAM (~600 MB) in sessions that never classify. Acceptable
  for the typical workflow.

---

## 4. Explicitly NOT doing (and why)

- **Broad unit-test suite.** Pipeline is still evolving; tests would rot.
  A single integration test (load fixed DICOM bundle, assert final label)
  is the only thing that pays for itself right now.
- **Rewriting the pure-Java DICOM parser** (`DicomExtractor.readModalityAndSOP`).
  Complex and fragile but works. Every observed failure is solved
  downstream by the Python `pydicom` fallback. Aesthetic refactor is a trap.
- **Switching the worker wire protocol to JSON.** Tab protocol is fine for
  this surface area. Adding Jackson means OSGi classloader headaches for
  zero correctness gain.
- **Building case management / database features.** Different product.
  Result export (3.4) gives 80% of the value with 5% of the effort.
- **Automatic telemetry.** Forensic data is special. Local-only crash dumps
  (3.7) are the right level until privacy counsel says otherwise.
- **PyTorch INT8 quantization of the ResNet.** PyTorch dynamic quant is weak
  on `Conv2d` (designed for `Linear`/`LSTM`); static quant needs a
  calibration dataset. Speculative chase; skip until there's a benchmark
  reason.
- **Refactoring `PythonWorker` into per-task workers.** One worker for all
  three tasks is the right granularity. Splitting triples lifecycle
  complexity for no measurable gain.
- **Eigen-CAM / Score-CAM as a Grad-CAM substitute.** See constraint §2.1.
  This is non-negotiable; do not silently weaken six months from now.
- **ONNX migration in any form.** See constraint §2.2.

---

## 5. Definition of done for each item

Each item above is "done" when:
1. Code shipped on master with a green `.\run_weasis.ps1 -Fast` build.
2. CLAUDE.md and this roadmap are updated to reflect the completion.
3. If user-visible: the change has been validated end-to-end via a real
   classification run on a real DICOM series (not a synthetic test).
4. If protocol-visible: `inference_worker.py` docstring is updated.

---

## 6. Pointers

- Authoritative architecture summary: `CLAUDE.md`
- Historical snapshot (with stale Python-script descriptions): `TECHNICAL_REPORT.md` (see banner at top)
- Wire protocol: `weasis-sex-classifier/src/main/resources/inference_worker.py` (top docstring)
- Process lifecycle and timeouts: `weasis-sex-classifier/src/main/java/org/weasis/sex/classifier/PythonWorker.java`
