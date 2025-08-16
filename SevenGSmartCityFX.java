/*
 * SevenGSmartCityFX.java
 * ------------------------------------------------------------
 * Java 17+ single-file simulator with JavaFX live dashboard and Prometheus metrics export.
 * 
 * Features:
 *  - 7G Smart City simulator (multi-band channels: THz + Optical)
 *  - Network slices (Safety/IoT, Holography, City IoT) with token-bucket shaping
 *  - Devices: Self-Driving Car, Drone, AR Glasses, Sensor Hub, Edge Server
 *  - Crypto layer: Mock PQC (XOR stream) or AES-GCM fallback
 *  - AI Orchestrator that auto-tunes slice bandwidths
 *  - JavaFX dashboard: live latency chart + slice bandwidth bar chart + counters
 *  - Prometheus metrics exporter on http://localhost:9400/metrics
 * 
 * Build & Run (Maven recommended because of JavaFX):
 * -------------------------------------------------
 * Option A: Maven (Linux/macOS/Windows, requires OpenJFX)
 *   1) Create a Maven project and use the provided pom.xml (see bottom of this file).
 *   2) Place this file under src/main/java/SevenGSmartCityFX.java
 *   3) mvn clean javafx:run
 *
 * Option B: Manual (requires OpenJFX installed & on module-path)
 *   javac --release 17 --module-path /path/to/javafx/lib --add-modules javafx.controls SevenGSmartCityFX.java
 *   java  --module-path /path/to/javafx/lib --add-modules javafx.controls SevenGSmartCityFX
 *
 * Notes:
 *  - If JavaFX isn't installed, use Maven option.
 *  - Prometheus endpoint: scrape http://localhost:9400/metrics
 *
 * Developer: Shiboshree Roy
 * Version:   1.1.0
 * License:   MIT
 */

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration as JDuration; // avoid clash with javafx.util.Duration
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SevenGSmartCityFX {

    /* ===========================
     * Messaging Domain
     * =========================== */
    static final class Plain {
        final String from, to, sliceId, kind;
        final byte[] body;
        Plain(String from, String to, String sliceId, String kind, byte[] body) {
            this.from = from; this.to = to; this.sliceId = sliceId; this.kind = kind; this.body = body;
        }
    }

    static final class CipherMsg {
        final String from, to, sliceId, kind;
        final byte[] ciphertext;
        final byte[] iv; // for AES-GCM; null if PQC mock
        final int plainSize;
        final Instant createdAt = Instant.now();
        CipherMsg(String from, String to, String sliceId, String kind, byte[] ciphertext, byte[] iv, int plainSize) {
            this.from = from; this.to = to; this.sliceId = sliceId; this.kind = kind;
            this.ciphertext = ciphertext; this.iv = iv; this.plainSize = plainSize;
        }
    }

    /* ===========================
     * Crypto Providers
     * =========================== */
    interface Crypto { CipherMsg seal(Plain p) throws Exception; Plain open(CipherMsg c) throws Exception; String name(); }

    static final class MockPQC implements Crypto {
        private final SecureRandom rng = new SecureRandom();
        private final byte[] k;
        MockPQC() { k = new byte[32]; rng.nextBytes(k); }
        public CipherMsg seal(Plain p) { byte[] buf = Arrays.copyOf(p.body, p.body.length); for (int i=0;i<buf.length;i++) buf[i]^=k[i%k.length]; return new CipherMsg(p.from,p.to,p.sliceId,p.kind,buf,null,p.body.length);}    
        public Plain open(CipherMsg c) { byte[] buf = Arrays.copyOf(c.ciphertext, c.ciphertext.length); for (int i=0;i<buf.length;i++) buf[i]^=k[i%k.length]; return new Plain(c.from,c.to,c.sliceId,c.kind,buf);}    
        public String name(){return "MockPQC";}
    }

    static final class AESGCM implements Crypto {
        private final SecretKey key; private final SecureRandom rng = new SecureRandom();
        AESGCM() throws Exception { KeyGenerator kg = KeyGenerator.getInstance("AES"); kg.init(256); key = kg.generateKey(); }
        public CipherMsg seal(Plain p) throws Exception { byte[] iv=new byte[12]; rng.nextBytes(iv); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv)); byte[] ct=c.doFinal(p.body); return new CipherMsg(p.from,p.to,p.sliceId,p.kind,ct,iv,p.body.length);}    
        public Plain open(CipherMsg cmsg) throws Exception { Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,cmsg.iv)); byte[] pt=c.doFinal(cmsg.ciphertext); return new Plain(cmsg.from,cmsg.to,cmsg.sliceId,cmsg.kind,pt);}    
        public String name(){return "AES-GCM";}
    }

    /* ===========================
     * Metrics & Registry
     * =========================== */
    static final class Metrics {
        final AtomicLong pktSent = new AtomicLong();
        final AtomicLong pktRecv = new AtomicLong();
        final AtomicLong pktDrop = new AtomicLong();
        final AtomicLong bytesSent = new AtomicLong();
        final AtomicLong bytesRecv = new AtomicLong();
        final AtomicLong latSumMs = new AtomicLong();
        final AtomicLong latCount = new AtomicLong();
        volatile double lastAvgLatencyMs = 0.0; // convenience for UI

        void onSend(int bytes){ pktSent.incrementAndGet(); bytesSent.addAndGet(bytes);}    
        void onRecv(CipherMsg c,long latencyMs){ pktRecv.incrementAndGet(); bytesRecv.addAndGet(c.plainSize); latSumMs.addAndGet(latencyMs); long n=latCount.incrementAndGet(); lastAvgLatencyMs = latSumMs.get()*1.0/n; }
        void onDrop(){ pktDrop.incrementAndGet(); }
        double avgLatency(){ long n=latCount.get(); return n==0?0.0:latSumMs.get()*1.0/n; }
        String snapshot(){ return "Sent="+pktSent.get()+" recv="+pktRecv.get()+" drop="+pktDrop.get()+" bytesSent="+bytesSent.get()+" bytesRecv="+bytesRecv.get()+" avgLatMs="+String.format(Locale.US,"%.2f",avgLatency()); }
    }

    static final class MetricsRegistry {
        final Metrics metrics = new Metrics();
        final Map<String, Slice> slices = new ConcurrentHashMap<>();
        static final MetricsRegistry INSTANCE = new MetricsRegistry();
        static MetricsRegistry get(){ return INSTANCE; }
    }

    /* ===========================
     * Slices & Channel
     * =========================== */
    static final class Slice {
        final String id, desc; volatile long bandwidthBps; final long bucketBytes; final long targetLatencyMs;
        Slice(String id,String desc,long bw,long bucket,long target){ this.id=id; this.desc=desc; this.bandwidthBps=bw; this.bucketBytes=bucket; this.targetLatencyMs=target; }
    }
    enum Band { THZ, OPTICAL }

    static final class MultiBandChannel implements Closeable {
        record BandParams(double baseLatencyMs,double jitterMs,double loss,long capacityBps){}
        private final Map<Band,BandParams> bands = new EnumMap<>(Band.class);
        private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(6);
        private final SecureRandom rng = new SecureRandom();
        private final Metrics metrics; private final Map<String,Bucket> buckets = new ConcurrentHashMap<>();
        private final AtomicBoolean open=new AtomicBoolean(true);
        MultiBandChannel(Metrics m){ this.metrics=m; }
        void config(Band b,BandParams p){ bands.put(b,p);}    
        void registerSlice(Slice s){ buckets.putIfAbsent(s.id,new Bucket(s)); MetricsRegistry.get().slices.putIfAbsent(s.id,s); }
        void transmit(Band b, CipherMsg msg, Node dst){ if(!open.get())return; BandParams p=bands.get(b); if(p==null){metrics.onDrop();return;} if(rng.nextDouble()<p.loss()){metrics.onDrop();return;} Bucket buck=buckets.get(msg.sliceId); if(buck==null||!buck.tryConsume(msg.ciphertext.length)){metrics.onDrop();return;} double lat=Math.max(0,p.baseLatencyMs()+rng.nextGaussian()*p.jitterMs()); double serMs=(msg.ciphertext.length*8000.0)/p.capacityBps(); long delayMs=Math.round(lat+serMs); exec.schedule(() -> { if(open.get()) dst.inbox.offer(msg); }, delayMs, TimeUnit.MILLISECONDS);}    
        public void close(){ open.set(false); exec.shutdownNow(); buckets.values().forEach(Bucket::shutdown);}    
        static final class Bucket { final Slice s; final ScheduledExecutorService ref=Executors.newSingleThreadScheduledExecutor(); final AtomicLong tokens; Bucket(Slice s){ this.s=s; this.tokens=new AtomicLong(s.bucketBytes); ref.scheduleAtFixedRate(() -> { long perTick=Math.max(1, s.bandwidthBps/8/20); tokens.accumulateAndGet(perTick,(cur,add)->Math.min(s.bucketBytes,cur+add)); },50,50,TimeUnit.MILLISECONDS);} boolean tryConsume(int n){ while(true){ long cur=tokens.get(); if(cur<n) return false; if(tokens.compareAndSet(cur,cur-n)) return true; } } void shutdown(){ ref.shutdownNow(); } }
    }

    /* ===========================
     * AI Orchestrator
     * =========================== */
    static final class Orchestrator implements Runnable, Closeable {
        private final List<Slice> slices; private final Metrics metrics; private final ScheduledExecutorService exec=Executors.newSingleThreadScheduledExecutor(); private final AtomicBoolean run=new AtomicBoolean(true);
        Orchestrator(List<Slice> s, Metrics m){ this.slices=s; this.metrics=m; }
        void start(){ exec.scheduleAtFixedRate(this,2,2,TimeUnit.SECONDS);}    
        public void run(){ if(!run.get()) return; double avgLat=metrics.avgLatency(); long drops=metrics.pktDrop.get(); for(Slice s: slices){ long bw=s.bandwidthBps; boolean needMore = avgLat > s.targetLatencyMs || drops>0; long newBw = needMore ? Math.min(bw + Math.max(150_000, bw/8), bw + 6_000_000) : Math.max(100_000, (long)(bw*0.96)); if(newBw!=bw){ s.bandwidthBps=newBw; System.out.printf(Locale.US, "[Orchestrator] %s bw -> %d bps (avgLat=%.2f drops=%d)\n", s.id, s.bandwidthBps, avgLat, drops); } } }
        public void close(){ run.set(false); exec.shutdownNow(); }
    }

    /* ===========================
     * Nodes
     * =========================== */
    static class Node implements Runnable, Closeable {
        final String name; final Map<String,Node> directory; final MultiBandChannel channel; final Crypto crypto; final Metrics metrics; final BlockingQueue<CipherMsg> inbox=new LinkedBlockingQueue<>(); final AtomicBoolean running=new AtomicBoolean(true);
        Node(String name, Map<String,Node> dir, MultiBandChannel ch, Crypto c, Metrics m){ this.name=name; this.directory=dir; this.channel=ch; this.crypto=c; this.metrics=m; }
        void send(Band band, Plain p) throws Exception { Node dst=directory.get(p.to); if(dst==null) throw new IllegalArgumentException("Unknown destination: "+p.to); CipherMsg cmsg=crypto.seal(p); metrics.onSend(p.body.length); channel.transmit(band,cmsg,dst);}    
        public void run(){ try{ while(running.get()){ CipherMsg cmsg=inbox.poll(100,TimeUnit.MILLISECONDS); if(cmsg==null) continue; long lat=JDuration.between(cmsg.createdAt, Instant.now()).toMillis(); metrics.onRecv(cmsg,lat); Plain p=crypto.open(cmsg); onMessage(p,lat); } } catch(InterruptedException ie){ Thread.currentThread().interrupt(); } catch(Exception e){ System.err.println(name+" error: "+e); } }
        void onMessage(Plain p,long latencyMs){ String prev = preview(p.body); System.out.printf(Locale.US, "[%s] <- (%s) slice=%s kind=%s latency=%dms : %s\n", name, p.from, p.sliceId, p.kind, latencyMs, prev); }
        public void close(){ running.set(false); }
    }

    static final class SelfDrivingCar extends Node { private final String sliceSafety, sliceHolo; private final String edge;
        SelfDrivingCar(String name, Map<String,Node> dir, MultiBandChannel ch, Crypto c, Metrics m, String sliceSafety, String sliceHolo, String edge){ super(name,dir,ch,c,m); this.sliceSafety=sliceSafety; this.sliceHolo=sliceHolo; this.edge=edge; }
        void sendTelemetry() throws Exception { send(Band.THZ, new Plain(name, edge, sliceSafety, "telemetry", telemetryJson(256))); }
        void sendHologramFrame() throws Exception { send(Band.OPTICAL, new Plain(name, edge, sliceHolo, "holo", hologramChunk(16*1024))); }
    }

    static final class DeliveryDrone extends Node { private final String sliceSafety; private final String edge;
        DeliveryDrone(String name, Map<String,Node> dir, MultiBandChannel ch, Crypto c, Metrics m, String sliceSafety, String edge){ super(name,dir,ch,c,m); this.sliceSafety=sliceSafety; this.edge=edge; }
        void sendTelemetry() throws Exception { send(Band.THZ, new Plain(name, edge, sliceSafety, "telemetry", telemetryJson(200))); }
    }

    static final class ARGlasses extends Node { private final String sliceHolo; private final String edge;
        ARGlasses(String name, Map<String,Node> dir, MultiBandChannel ch, Crypto c, Metrics m, String sliceHolo, String edge){ super(name,dir,ch,c,m); this.sliceHolo=sliceHolo; this.edge=edge; }
        void sendHologramFrame() throws Exception { send(Band.OPTICAL, new Plain(name, edge, sliceHolo, "holo", hologramChunk(24*1024))); }
    }

    static final class EdgeServer extends Node { EdgeServer(String name, Map<String,Node> dir, MultiBandChannel ch, Crypto c, Metrics m){ super(name,dir,ch,c,m);} }

    /* ===========================
     * Payload Generators
     * =========================== */
    static byte[] telemetryJson(int approxBytes){ String s = String.format(Locale.US, "{\"type\":\"telemetry\",\"lat\":%.5f,\"lon\":%.5f,\"spd\":%d,\"ts\":\"%s\",\"status\":\"OK\"}", rand(-90,90), rand(-180,180), ThreadLocalRandom.current().nextInt(0,150), Instant.now()); return padBytes(s.getBytes(), approxBytes);}    
    static byte[] hologramChunk(int approxBytes){ ByteBuffer bb=ByteBuffer.allocate(64); bb.putLong(System.nanoTime()); bb.putInt(approxBytes); bb.putInt(ThreadLocalRandom.current().nextInt()); String head=Base64.getEncoder().encodeToString(bb.array()); byte[] prefix=("HOLO:"+head+":").getBytes(); byte[] padded=padBytes(new byte[0], Math.max(0, approxBytes-prefix.length)); byte[] out=new byte[prefix.length+padded.length]; System.arraycopy(prefix,0,out,0,prefix.length); System.arraycopy(padded,0,out,prefix.length,padded.length); return out; }
    static byte[] padBytes(byte[] src,int size){ byte[] out=new byte[Math.max(size, src.length)]; System.arraycopy(src,0,out,0,Math.min(src.length,out.length)); for(int i=src.length;i<out.length;i++) out[i]=(byte)('A'+(i%23)); return out; }
    static double rand(double lo,double hi){ return lo + ThreadLocalRandom.current().nextDouble()*(hi-lo); }
    static String preview(byte[] data){ int n=Math.min(40,data.length); return "bytes="+data.length+" head(b64)="+Base64.getEncoder().encodeToString(Arrays.copyOf(data,n)); }

    /* ===========================
     * Prometheus Exporter
     * =========================== */
    static final class PromExporter implements Closeable {
        private final HttpServer server;
        private final Metrics metrics;
        private final Map<String, Slice> slices;
        PromExporter(int port, Metrics m, Map<String,Slice> slices) throws IOException {
            this.metrics=m; this.slices=slices; server=HttpServer.create(new InetSocketAddress(port),0); server.createContext("/metrics", new Handler()); server.setExecutor(Executors.newSingleThreadExecutor()); server.start(); System.out.println("[Prometheus] Listening on http://localhost:"+port+"/metrics"); }
        class Handler implements HttpHandler { public void handle(HttpExchange ex) throws IOException { String body = buildMetrics(); ex.getResponseHeaders().add("Content-Type","text/plain; version=0.0.4"); byte[] b=body.getBytes(); ex.sendResponseHeaders(200,b.length); try(OutputStream os=ex.getResponseBody()){ os.write(b);} } }
        private String buildMetrics(){ StringBuilder sb=new StringBuilder(); sb.append("# HELP seven_g_packets_total Packets counters\n"); sb.append("# TYPE seven_g_packets_total counter\n"); sb.append("seven_g_packets_total{type=\"sent\"} ").append(metrics.pktSent.get()).append('\n'); sb.append("seven_g_packets_total{type=\"recv\"} ").append(metrics.pktRecv.get()).append('\n'); sb.append("seven_g_packets_total{type=\"drop\"} ").append(metrics.pktDrop.get()).append('\n'); sb.append("# HELP seven_g_bytes_total Bytes counters\n"); sb.append("# TYPE seven_g_bytes_total counter\n"); sb.append("seven_g_bytes_total{type=\"sent\"} ").append(metrics.bytesSent.get()).append('\n'); sb.append("seven_g_bytes_total{type=\"recv\"} ").append(metrics.bytesRecv.get()).append('\n'); sb.append("# HELP seven_g_avg_latency_ms Average latency in ms\n"); sb.append("# TYPE seven_g_avg_latency_ms gauge\n"); sb.append("seven_g_avg_latency_ms ").append(String.format(Locale.US, "%.4f", metrics.avgLatency())).append('\n'); sb.append("# HELP seven_g_slice_bandwidth_bps Current slice bandwidth settings\n"); sb.append("# TYPE seven_g_slice_bandwidth_bps gauge\n"); for (Slice s: slices.values()){ sb.append("seven_g_slice_bandwidth_bps{slice=\"").append(s.id).append("\"} ").append(s.bandwidthBps).append('\n'); } return sb.toString(); }
        public void close(){ server.stop(0); }
    }

    /* ===========================
     * JavaFX Dashboard
     * =========================== */
    public static class DashboardApp extends Application {
        private LineChart<Number,Number> latencyChart;
        private XYChart.Series<Number,Number> latencySeries;
        private BarChart<String,Number> bwChart;
        private Label counters;
        private Timeline ticker;
        @Override public void start(Stage stage){
            NumberAxis x = new NumberAxis(); x.setLabel("Time (s)");
            NumberAxis y = new NumberAxis(); y.setLabel("Avg Latency (ms)");
            latencyChart = new LineChart<>(x,y); latencyChart.setTitle("Average Latency"); latencySeries=new XYChart.Series<>(); latencySeries.setName("Latency"); latencyChart.getData().add(latencySeries);

            CategoryAxis cx = new CategoryAxis(); NumberAxis cy = new NumberAxis();
            bwChart = new BarChart<>(cx, cy); bwChart.setTitle("Slice Bandwidth (bps)");

            counters = new Label("Loading metrics...");

            VBox right = new VBox(10, bwChart, counters); right.setPrefWidth(420);
            BorderPane root = new BorderPane(); root.setCenter(latencyChart); root.setRight(right);
            root.setTop(new HBox(new Label("  7G Smart City – Live Dashboard")));

            stage.setScene(new Scene(root, 1100, 600));
            stage.setTitle("7G Smart City Dashboard – Shiboshree Roy");
            stage.show();

            // periodic updates
            ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateCharts()));
            ticker.setCycleCount(Timeline.INDEFINITE);
            ticker.play();
        }

        private int t = 0;
        private void updateCharts(){
            Metrics m = MetricsRegistry.get().metrics;
            Map<String, Slice> slices = MetricsRegistry.get().slices;
            double lat = m.avgLatency();
            latencySeries.getData().add(new XYChart.Data<>(t++, lat));
            if (latencySeries.getData().size() > 60) latencySeries.getData().remove(0);

            bwChart.getData().clear();
            XYChart.Series<String,Number> s = new XYChart.Series<>(); s.setName("Bandwidth");
            for (Slice sl : slices.values()) { s.getData().add(new XYChart.Data<>(sl.id, sl.bandwidthBps)); }
            bwChart.getData().add(s);

            counters.setText(String.format(Locale.US,
                    "Packets sent=%d recv=%d drop=%d | bytes sent=%d recv=%d | avg latency=%.2f ms",
                    m.pktSent.get(), m.pktRecv.get(), m.pktDrop.get(), m.bytesSent.get(), m.bytesRecv.get(), lat));
        }

        @Override public void stop(){ if (ticker!=null) ticker.stop(); }
    }

    /* ===========================
     * MAIN: Build City, Run Sim + Dashboard + Prometheus
     * =========================== */
    public static void main(String[] args) throws Exception {
        boolean usePQC = true; // toggle PQC mock vs AES-GCM
        int simSeconds = 0;    // 0 = run until window is closed

        Metrics metrics = MetricsRegistry.get().metrics;
        Crypto crypto = usePQC ? new MockPQC() : new AESGCM();
        System.out.println("Crypto Provider: "+crypto.name());

        // Slices
        Slice sliceSafety = new Slice("slice-safety","Safety-critical IoT", 3_000_000L, 128*1024, 5);
        Slice sliceHolo   = new Slice("slice-holo",  "Holography/3D",    25_000_000L,1024*1024, 8);
        Slice sliceCity   = new Slice("slice-city",  "City sensors",       2_000_000L, 64*1024, 15);

        // Channel
        MultiBandChannel ch = new MultiBandChannel(metrics);
        ch.config(Band.THZ,     new MultiBandChannel.BandParams(1.0, 0.3, 0.003, 80_000_000L));
        ch.config(Band.OPTICAL, new MultiBandChannel.BandParams(2.0, 0.5, 0.001,150_000_000L));
        ch.registerSlice(sliceSafety); ch.registerSlice(sliceHolo); ch.registerSlice(sliceCity);

        // Directory & Nodes
        Map<String,Node> dir = new ConcurrentHashMap<>();
        EdgeServer edge = new EdgeServer("Edge-DC", dir, ch, crypto, metrics);
        SelfDrivingCar car = new SelfDrivingCar("Car-X1", dir, ch, crypto, metrics, sliceSafety.id, sliceHolo.id, edge.name);
        DeliveryDrone drone = new DeliveryDrone("Drone-D7", dir, ch, crypto, metrics, sliceSafety.id, edge.name);
        ARGlasses glasses = new ARGlasses("AR-G1", dir, ch, crypto, metrics, sliceHolo.id, edge.name);
        Node sensorHub = new Node("SensorHub", dir, ch, crypto, metrics);
        dir.put(edge.name,edge); dir.put(car.name,car); dir.put(drone.name,drone); dir.put(glasses.name,glasses); dir.put(sensorHub.name,sensorHub);

        ExecutorService pool = Executors.newFixedThreadPool(5);
        pool.submit(edge); pool.submit(car); pool.submit(drone); pool.submit(glasses); pool.submit(sensorHub);

        Orchestrator orch = new Orchestrator(Arrays.asList(sliceSafety, sliceHolo, sliceCity), metrics); orch.start();

        // Traffic generators
        ScheduledExecutorService tg = Executors.newScheduledThreadPool(4);
        tg.scheduleAtFixedRate(() -> safe(() -> car.sendTelemetry()), 100, 20, TimeUnit.MILLISECONDS);
        tg.scheduleAtFixedRate(() -> safe(() -> car.sendHologramFrame()), 200, 50, TimeUnit.MILLISECONDS);
        tg.scheduleAtFixedRate(() -> safe(() -> drone.sendTelemetry()), 150, 33, TimeUnit.MILLISECONDS);
        tg.scheduleAtFixedRate(() -> safe(() -> glasses.sendHologramFrame()), 250, 42, TimeUnit.MILLISECONDS);
        tg.scheduleAtFixedRate(() -> safe(() -> sensorHub.send(Band.THZ, new Plain("SensorHub", edge.name, sliceCity.id, "telemetry", telemetryJson(200)))), 300, 100, TimeUnit.MILLISECONDS);

        // Start Prometheus exporter
        PromExporter exporter = new PromExporter(9400, metrics, MetricsRegistry.get().slices);

        System.out.println("=== 7G Smart City Simulator (FX) Started ===");

        // Launch JavaFX UI on the FX thread
        Platform.startup(() -> {
            try { new DashboardApp().start(new Stage()); } catch (Exception e) { e.printStackTrace(); }
        });

        if (simSeconds > 0) {
            // Optional timed run
            Thread.sleep(simSeconds * 1000L);
            shutdown(tg, pool, orch, ch, exporter);
        }
        // Otherwise, keep running until the FX window is closed; add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(tg, pool, orch, ch, exporter)));
    }

    private static void shutdown(ScheduledExecutorService tg, ExecutorService pool, Orchestrator orch, MultiBandChannel ch, PromExporter exporter){
        try {
            tg.shutdownNow(); pool.shutdownNow(); orch.close(); ch.close(); exporter.close();
            System.out.println("\n=== Final Metrics ===\n"+MetricsRegistry.get().metrics.snapshot());
            System.out.println("=== 7G Smart City Simulator Finished ===");
        } catch (Exception ignored) {}
    }

    private static void safe(ThrowingRunnable r){ try{ r.run(); } catch(Exception ignored){} }
    @FunctionalInterface interface ThrowingRunnable{ void run() throws Exception; }
}

/* =====================================================================
 * Optional Maven pom.xml (place in your project's pom.xml)
 * =====================================================================
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.smartcity</groupId>
  <artifactId>seven-g-smartcity-fx</artifactId>
  <version>1.1.0</version>
  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <javafx.version>22.0.2</javafx.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-controls</artifactId>
      <version>${javafx.version}</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-maven-plugin</artifactId>
        <version>0.0.9</version>
        <configuration>
          <mainClass>SevenGSmartCityFX</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
*/
