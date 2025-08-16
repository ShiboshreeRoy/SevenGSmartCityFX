# 📡 Seven GSmart City FX

![Java](https://img.shields.io/badge/Java-17-orange?logo=java)
![JavaFX](https://img.shields.io/badge/JavaFX-UI-blue?logo=openjdk)
![Prometheus](https://img.shields.io/badge/Prometheus-Metrics-red?logo=prometheus)
![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)

---

## 🚀 Project Overview

**Seven GSmart City FX** is an advanced **6G/7G network simulation platform** built with **Java 17 + JavaFX**.
It demonstrates futuristic **smart city communication orchestration**, **network slicing**, and **live performance monitoring**.

The system includes:

* 📊 **Real-time dashboard (JavaFX)** – shows latency trends & slice bandwidths.
* ⚙️ **AI-inspired Orchestrator** – dynamically allocates resources across slices.
* 🌐 **Prometheus Metrics Exporter** – live metrics on `http://localhost:9400/metrics`.
* 🏙 **Future-Ready Simulation** – models URLLC, mMTC, and eMBB network slices.

---

## 🛠 Tech Stack

* **Java 17 (JDK)** – Core language
* **JavaFX** – Interactive dashboard UI
* **Prometheus Client (io.prometheus.simpleclient)** – Metrics endpoint
* **Maven** – Build & dependency management

---

## 📂 Project Structure

```
SevenGSmartCityFX/
 ├── src/
 │    └── main/
 │         └── java/
 │              └── SevenGSmartCityFX.java   # Full simulator + dashboard
 ├── pom.xml                                # Maven dependencies & config
 ├── README.md                              # Documentation
 └── LICENSE                                # MIT License
```

---

## ⚡ Installation & Run

### 1️⃣ Clone repo

```bash
git clone https://github.com/ShiboshreeRoy/SevenGSmartCityFX.git
cd SevenGSmartCityFX
```

### 2️⃣ Build with Maven (recommended)

```bash
mvn clean javafx:run
```

### 3️⃣ Or manual run

```bash
javac --release 17 --module-path /path/to/javafx/lib --add-modules javafx.controls src/main/java/SevenGSmartCityFX.java
java  --module-path /path/to/javafx/lib --add-modules javafx.controls SevenGSmartCityFX
```

---

## 📊 Live Dashboard

* **Latency Line Chart** – Real-time end-to-end latency (ms).
* **Slice Bandwidth Bars** – eMBB, URLLC, mMTC slice allocation.
---

## 📡 Metrics (Prometheus)

Available at → [http://localhost:9400/metrics](http://localhost:9400/metrics)

Example:

```
# HELP network_latency_ms Simulated network latency
# TYPE network_latency_ms gauge
network_latency_ms 12.5
```

---

## 👨‍💻 Developer

**Shiboshree Roy**

* 🌍 Futuristic Software Engineer
* 💡 Passionate about **6G/7G research**, **Smart Cities**, and **AI-driven networks**

---

## 📜 License

This project is licensed under the [MIT License](LICENSE).

