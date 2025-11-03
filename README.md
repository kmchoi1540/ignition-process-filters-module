# **ignition-process-filters-module**

Custom Ignition filter and buffer module for process control.

| Version              | Branch        | Notes                            |
|----------------------|---------------|----------------------------------|
| <center>8.1</center> | <center>ignition-8.1</center>  | Stable production build          |
| <center>8.3</center> | <center>ignition-8.3</center>  | Identical, Updated SDK / Java 17 |


This repository contains the **core filtering and buffer management library** for Inductive Automation **Ignition 8.1/8.3** modules.  
It provides modular and reusable components for signal filtering, data buffering, and UUID-based object lifecycle management, designed for high-performance process control applications.

---

## 📁 Project Structure

kaychoi-filter/  
└─ common/  
&ensp;└─ src/main/java/com/kaychoi/ignition/pid/common/  
&emsp;├─ BasicStoreDataManager.java  
&emsp;├─ CommonTagUtils.java  
&emsp;├─ StoreData.java  
&emsp;├─ StoreDataFunction.java  
&emsp;├─ TimedObjectManager.java  
&emsp;├─ UUIDKeyManager.java  
&emsp;└─ filter/  
&emsp;&ensp;├─ AbstractFilter.java  
&emsp;&ensp;├─ ButterworthIIRFilter.java  
&emsp;&ensp;├─ CurrentWeightedMovingAverageFilter.java  
&emsp;&ensp;├─ CustomFilterFunction.java  
&emsp;&ensp;├─ FilterUtils.java  
&emsp;&ensp;└─ WeightedMovingAverageFilter.java  

## 🧩 Example UDT and Tag Resources

Sample tag configuration for testing this module is included at:
resources/tags/tags.json

---

## ✨ Features

### 🔹 Core Data Management
- **StoreData / TimedObjectManager / UUIDKeyManager**
    - Provides UUID-based object lifecycle control and time-to-live (TTL) management.
    - Supports both synchronous and thread-safe modes.
    - Maintains sliding data buffers for time-series filtering.

### 🔹 Filtering Algorithms
- **WeightedMovingAverageFilter** — classic weighted average smoothing.
- **CurrentWeightedMovingAverageFilter** — emphasizes recent samples.
- **ButterworthIIRFilter** — low-pass Infinite Impulse Response filter for signal stabilization.
- **AbstractFilter / FilterUtils** — shared base logic and utility methods.

## 🧩 Module Purpose

This library is intended to serve as a **shared “common” module** within a larger Ignition SDK project, typically imported by:

- `gateway` scope (for execution logic)
- `designer` scope (for UI integration)
- `client` scope (optional)

The `common` module ensures deterministic, reusable logic across all scopes.

## 🧩 Expression Functions Overview

Two expression functions are exposed for direct use inside Ignition bindings or scripts.

| Function	                     |Signature	|Purpose|
|-------------------------------|----------|-------|
| <center>`storeData`</center>|`storeData(enable:boolean, size:int, input:double, uniqueId:string)`|	Buffers recent input values by UUID key. Returns the current buffer as a list.
| <center>`customFilter`</center>|`customFilter(enable:boolean, mode:int, size:int, args:list, inputs:list, uniqueId:string)`|	Applies a selected filter algorithm to buffered or supplied inputs and returns the filtered value.

### 🔧 Parameter Summary
| Parameter  | Type         | Required | Description                                                                                                                                                                                                                                                                                    |
| ---------- | ------------ | :------: |------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <center>`enable`</center>| boolean      |     ✅    | If `false`, clears internal state and returns the last input value.                                                                                                                                                                                                                            |
| <center>`mode`</center>| int          |     ✅    | Filter selection (1–3).                                                                                                                                                                                                                                                                        |
| <center>`size`</center>| int          |     ✅    | Maximum buffer length (window size).                                                                                                                                                                                                                                                           |
| <center>`args`</center>| list         |     ✅    | Filter-specific parameters (see table below).                                                                                                                                                                                                                                                  |
| <center>`inputs`</center>| list<double> |     ✅    | Ordered time-series values (oldest → newest).                                                                                                                                                                                                                                                  |
| <center>`uniqueId`</center>| string       |     ⚪    | Optional in `customFilter`, required in `storeData`. <br>Defines the persistent buffer key.<br>**Note:** If omitted in `customFilter`, a random UUID is automatically generated each cycle <br>— this resets the buffer on every evaluation, so filters like WMA may not operate continuously. |

### ⚙️ Filter Modes and Arguments
|  Mode  | Filter Type                                | `args` format                                            | Description                                                                                                                                                                                |
| :----: | ------------------------------------------ | -------------------------------------------------------- |--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|  **1** | **Current Weighted Moving Average (CWMA)** | `[α]`<br>Example: `[0.3]`                                | Blends the current sample with the average of previous values.<br>`α ∈ [0,1]` → higher α = faster response, lower α = smoother output.                                                     |
|  **2** | **Weighted Moving Average (WMA)**          | `[windowSize]`<br>Example: `[10]`                        | Linearly increasing weights toward the newest samples.<br>Emphasizes recent trends while suppressing random noise.                                                                         |
|  **3** | **Butterworth IIR (2nd-order)**            | `[fsHz, fcHz]`<br>Example: `[10.0, 1.0]`                 | Classic low-pass IIR filter.<br>Derives coefficients dynamically from sampling (`fs`) and cutoff (`fc`) frequencies.                                                                       |
| **3A** | **Adaptive Butterworth IIR**               | `[fsHz, fcHz, adaptGain]`<br>Example: `[10.0, 1.0, 0.2]` | Same as Mode 3, but cutoff `fc` adapts to input variance.<br>When signal fluctuates → `fc↑` (faster), when stable → `fc↓` (smoother).                                                      |

### ✅ Example Usage

1. Buffered filtering (recommended)  
   ```java
   customFilter(
    true, 3, 20, [10.0, 1.5],  
    storeData(true, 20, {[YourTag]}, "loop1-pv"), "loop1-pv"
   )
   ```  

2. One-shot filtering with manual list  
   ```java
   customFilter(
    true, 1, 20, [0.3],  
    [23.2, 23.1, 23.0, 22.9, 23.0, 23.1], "loop1-pv"
   )
   ```  

### 🧩 Internal Architecture Overview  

This module follows a modular and layered architecture to keep filtering logic thread-safe, stateful, and reusable across Ignition scopes (common, gateway, designer).

|            Expression Layer            |
|:--------------------------------------:|
|       CustomFilterFunction.java        |         
|         StoreDataFunction.java         |           
|              **↓ calls**               |

|                      Core Logic Layer                      |
|:----------------------------------------------------------:|
|    StoreData.java  → maintains rolling buffer of inputs    |
| TimedObjectManager.java  → handles TTL & instance cleanup  |
|  UUIDKeyManager.java  → maps uniqueId → object reference   |
|                    **↓ interacts with**                    |

|                    Filter Engine Layer                     |
|:----------------------------------------------------------:|
|      AbstractFilter.java  → base template for filters      |
|              WeightedMovingAverageFilter.java              |
|          CurrentWeightedMovingAverageFilter.java           |
|                 ButterworthIIRFilter.java                  |
| FilterUtils.java  **→ helper functions (array ops, etc.)** |

### 🧠 Logical Flow  

1. **Expression Layer (Ignition Binding)**

`StoreDataFunction` collects incoming numeric tag values over time.

`CustomFilterFunction` retrieves the corresponding UUID-managed buffer and applies the chosen filter.

2. **Core Logic Layer**

`StoreData` maintains a bounded deque (latest → oldest values).

`TimedObjectManager` automatically cleans expired UUID instances (based on TTL).

`UUIDKeyManager` ensures that each expression function instance remains independent even with identical parameters.

3. **Filter Engine Layer**  

`Each filter` (WMA, CWMA, Butterworth) extends AbstractFilter.

`FilterUtils` converts input lists and computes coefficients.
**Filters return a single numeric output (the latest filtered result).**

---

## 🧮 Filter Mathematics Summary

This section provides concise mathematical representations of the implemented filters.
Each operates on the most recent N samples in the input buffer.

| Mode   | Filter Type                                | Formula                                                                   | Description                                                                                                                                                                                                                                          |
| ------ | ------------------------------------------ | ------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **1**  | **Current Weighted Moving Average (CWMA)** | `yₙ = α·xₙ + (1 − α)·x̄ₙ₋₁` <br> where `x̄ₙ₋₁` = mean of previous samples | Blends the **latest sample** `xₙ` with the **average of all previous samples**.<br>`α ∈ [0,1]` controls responsiveness.<br>Higher α → faster response; lower α → smoother output.                                                                    |
| **2**  | **Weighted Moving Average (WMA)**          | `yₙ = (Σ wᵢ·xᵢ) / (Σ wᵢ)`<br>where `wᵢ = i`                               | Computes a **linearly weighted mean** over the last N samples.<br>Recent samples have stronger influence. Provides short-term smoothing without phase distortion.                                                                                    |
| **3**  | **Butterworth IIR (2nd-Order Low-Pass)**   | `yₙ = b₀xₙ + b₁xₙ₋₁ + b₂xₙ₋₂ − a₁yₙ₋₁ − a₂yₙ₋₂`                           | Classic **2nd-order recursive low-pass** design.<br>Coefficients are derived from cutoff ratio `(fc / fs)`. <br>Produces smooth roll-off with minimal ripple and stable phase.                                                                       |
| **3A** | **Adaptive Butterworth IIR**               | `f_c(dyn) = f_c + g·√σ²`<br>where `σ² = (1/N) Σ (xᵢ − x̄)²`               | Auto-adjusts cutoff `fc` based on signal variance.<br>When input changes rapidly → `fc` increases for faster tracking.<br>When stable → `fc` decreases for stronger smoothing.<br>Uses **N (not N−1)** for variance to maintain real-time stability. |

### 📈 Visual Concept Summary

Raw Input Series (Examples)  
│  
├──► **CWMA** (`α` = 0.3)  
│&emsp;&emsp;Blends the current sample with the average of previous data.  
│&emsp;&emsp;Provides smooth exponential-like decay while maintaining responsiveness.  
│  
├──► **WMA** (`window` = 10)  
│&emsp;&emsp;Applies a linearly increasing weight to recent samples.  
│&emsp;&emsp;Reduces noise while emphasizing short-term trends.  
│  
├──► **Butterworth IIR** (`fc` = 1 Hz, `fs` = 10 Hz)  
│&emsp;&emsp;2nd-order low-pass response with flat passband and stable phase characteristics.  
│&emsp;&emsp;Smoothly attenuates high-frequency components without overshoot.  
│  
└──► **Adaptive Butterworth** (`fc` = 1 Hz, `fs` = 10 Hz, `g` = 0.3)  
&emsp;&emsp;Adjusts cutoff dynamically based on input variance.  
&emsp;&emsp;Faster when signal changes quickly, smoother when stable.  


### 🧠 Comparison Summary

| Property             | CWMA                              | WMA                         | Butterworth IIR            | Adaptive Butterworth                        |
| -------------------- | --------------------------------- | --------------------------- | -------------------------- | ------------------------------------------- |
| **Type**             | Exponential weighted average      | Linear weighted average     | 2nd-order recursive filter | Adaptive 2nd-order recursive filter         |
| **Core Parameter**   | α (0–1)                           | Window size (N)             | fc, fs                     | fc, fs, adaptGain                           |
| **Memory Usage**     | 1 running mean                    | N-sample buffer             | 2 input + 2 output states  | Same as Butterworth                         |
| **Responsiveness**   | Adjustable via α                  | Fixed by window             | Tunable via fc/fs          | Auto-adjusted by variance                   |
| **Smoothness**       | Medium–High                       | Medium                      | High                       | Adaptive (Low–High)                         |
| **Phase Delay**      | Minimal                           | Moderate                    | Moderate–High              | Moderate–High                               |
| **Computation Cost** | O(1)                              | O(N)                        | O(1)                       | O(1) + variance calc (O(N))                 |
| **Best Use Case**    | Simple smoothing / trend blending | Weighted short-term average | Stable low-pass filtering  | Adaptive noise filtering or dynamic signals |



### 🧩 Practical Guidelines

For real-time process values (temperature, flow, etc.), use `CWMA` **(mode 1)** with α ≈ 0.2–0.4.

For batch or averaged sensor data, use `WMA` **(mode 2)** with window size ≈ size/2.

For high-frequency noisy signals, use `Butterworth` **(mode 3)** with fc/fs ratio ≈ 0.05–0.1.

---

## ⚠️ Notes

- **Initial Evaluation Behavior**  
On the very first execution cycle after deployment or tag restart, the expression output may show a transient deviation.  
This occurs because Ignition’s expression engine initializes function contexts before the data buffer is fully populated.  
Subsequent cycles will stabilize as the StoreData buffer and filter state converge.  
  
- **Runtime Consistency**  
Once the first valid dataset is accumulated, the output becomes deterministic and stable for continuous operation.  
Resetting the enable flag or changing the uniqueId will intentionally reinitialize the filter.
  
- **Intended Audience**  
This module is designed primarily for research, prototyping, and educational environments—for studying signal processing and control concepts inside Ignition.  
While it demonstrates industrial-grade coding practices (thread safety, memory management, UUID lifecycle control), it has not been validated for certified industrial safety or mission-critical process control.  

#### ✅ Use it confidently for R&D, academic demonstration, or advanced SCADA experiments.
#### ⚠️ For production or regulated industrial deployment, a formal validation and QA process is recommended.

---

