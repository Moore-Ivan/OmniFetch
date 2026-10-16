package com.downloader.protocol;

import com.downloader.bencode.BencodeParser;
import com.downloader.model.ProgressCallback;
import com.downloader.util.FileUtils;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * BitTorrent / 磁力链接下载器
 * 实现 Peer Wire Protocol：
 * <ul>
 *   <li>握手后持续消费 bitfield / have / choke / unchoke / keep-alive 消息，仅在 unchoke 时发 request；</li>
 *   <li>分片通过全局认领队列在 Peer 间调度，失败分片重新入队并限制单分片最大尝试次数；</li>
 *   <li>支持多文件种子（按 info.files 建立目录结构，分片可跨文件边界写入）；</li>
 *   <li>每个分片 SHA-1 校验通过后才写盘，全部分片完成才回调 onComplete，杜绝假完成。</li>
 * </ul>
 * 注意：磁力链接需要 BEP-9 元数据交换（未实现），无元数据时显式报错。
 */
public class BitTorrentDownloader implements DownloadProtocol {

    private final String source;
    private final String savePath;
    private final ProgressCallback callback;
    private volatile boolean cancelled = false;

    private byte[] peerId;
    private TorrentMeta meta;
    private ExecutorService executor;
    private final List<PeerConnection> activePeers =
            Collections.synchronizedList(new ArrayList<>());

    private static final int DEFAULT_PIECE_LENGTH = 262_144; // 256 KB
    private static final int MAX_PEERS = 30;
    private static final int BLOCK_SIZE = 16_384;       // 16 KB
    /** 单个分片在多个 Peer 间的最大尝试次数，超过即放弃整个任务 */
    private static final int MAX_PIECE_ATTEMPTS = 5;
    /** 等待 unchoke / 单个分片完成的超时 */
    private static final int PIECE_TIMEOUT_MS = 60_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 8_000;

    public BitTorrentDownloader(String source, String savePath,
                                ProgressCallback cb) {
        this.source = source;
        this.savePath = savePath;
        this.callback = cb;
        this.peerId = generatePeerId();
    }

    @Override
    public void download() throws Exception {
        callback.onStatusUpdate("解析种子文件...");

        // 1. 解析元信息
        if (source.startsWith("magnet:")) {
            meta = parseMagnetLink(source);
        } else {
            meta = parseTorrentFile(source);
        }

        // 磁力链接无法直接获得 piece 哈希（需要 BEP-9 元数据交换，尚未实现），
        // 若继续执行会出现 0 字节"假完成"，必须显式报错
        if (meta.infoHash == null || meta.pieceHashes.isEmpty()) {
            throw new IOException(
                    "无法获取种子元数据（磁力链接需要 BEP-9 元数据交换支持），请改用 .torrent 文件");
        }

        callback.onConnected(meta.name, meta.totalLength);

        // 2. 查询 Tracker
        callback.onStatusUpdate("连接 Tracker...");
        List<InetSocketAddress> peers = queryTracker(meta, peerId, "started");
        if (peers.isEmpty()) {
            throw new IOException("未找到可用的 Peer");
        }
        callback.onStatusUpdate("发现 " + peers.size() + " 个 Peer");

        // 3. 初始化 Piece 调度状态
        int pieceCount = meta.pieceHashes.size();
        boolean[] pieceDone = new boolean[pieceCount];

        // 分片认领队列：同一个分片同一时刻只被一个 Peer 下载，失败后重新入队
        final Object queueLock = new Object();
        final Deque<Integer> pieceQueue = new ArrayDeque<>();
        final Map<Integer, Integer> attempts = new HashMap<>();
        final AtomicInteger permanentlyFailed = new AtomicInteger(0);
        for (int i = 0; i < pieceCount; i++) {
            pieceQueue.add(i);
        }

        // 4. 建立文件落盘通道（单文件 / 多文件目录）
        try (FileSink sink = createFileSink(meta, savePath)) {
            executor = Executors.newFixedThreadPool(Math.min(MAX_PEERS, Math.max(2, peers.size())));
            Semaphore peerLimit = new Semaphore(MAX_PEERS);
            AtomicLong downloaded = new AtomicLong(0);
            long startTime = System.currentTimeMillis();
            AtomicLong lastUpdate = new AtomicLong(startTime);
            AtomicLong lastBytes = new AtomicLong(0);

            // 认领一个该 Peer 持有的、未达尝试上限的分片
            java.util.function.ToIntFunction<PeerConnection> claim = pc -> {
                synchronized (queueLock) {
                    for (Iterator<Integer> it = pieceQueue.iterator(); it.hasNext(); ) {
                        Integer i = it.next();
                        int tried = attempts.getOrDefault(i, 0);
                        if (tried >= MAX_PIECE_ATTEMPTS) {
                            continue;
                        }
                        BitSet has = pc.availablePieces;
                        if (has != null && !has.get(i)) {
                            continue;
                        }
                        it.remove();
                        attempts.merge(i, 1, Integer::sum);
                        return i;
                    }
                    return -1;
                }
            };

            for (InetSocketAddress peerAddr : peers) {
                if (cancelled) break;
                peerLimit.acquire();
                executor.submit(() -> {
                    PeerConnection pc = null;
                    try {
                        pc = new PeerConnection(peerAddr, meta, peerId, pieceCount);
                        pc.connect();
                        activePeers.add(pc);

                        int pieceIndex;
                        while (!cancelled && (pieceIndex = claim.applyAsInt(pc)) >= 0) {
                            int pLen = meta.pieceLength(pieceIndex, pieceCount,
                                    meta.totalLength);
                            boolean success = false;
                            String err = null;
                            byte[] data = null;
                            try {
                                data = pc.downloadPiece(pieceIndex, pLen);
                                if (data != null
                                        && verifyPiece(data, meta.pieceHashes.get(pieceIndex))) {
                                    success = true;
                                } else if (data == null) {
                                    err = "Peer 未返回分片数据";
                                } else {
                                    err = "分片哈希校验失败";
                                }
                            } catch (Exception e) {
                                err = e.getMessage();
                            }

                            if (success) {
                                long position = (long) pieceIndex * meta.pieceLength;
                                sink.write(position, data, 0, data.length);

                                boolean notify;
                                synchronized (pieceDone) {
                                    notify = !pieceDone[pieceIndex];
                                    pieceDone[pieceIndex] = true;
                                }
                                if (notify) {
                                    long dl = downloaded.addAndGet(data.length);
                                    long now = System.currentTimeMillis();
                                    long lu = lastUpdate.get();
                                    if (now - lu >= 300
                                            && lastUpdate.compareAndSet(lu, now)) {
                                        long speed = (long) ((dl - lastBytes.get())
                                                * 1000.0 / (now - lu));
                                        callback.onProgress(dl, meta.totalLength, speed);
                                        lastBytes.set(dl);
                                    }
                                }
                            } else {
                                // 失败分片重新入队（优先重试）；超过上限则计入永久失败
                                synchronized (queueLock) {
                                    int tried = attempts.getOrDefault(pieceIndex, 0);
                                    if (!pieceDone[pieceIndex] && tried < MAX_PIECE_ATTEMPTS) {
                                        pieceQueue.addFirst(pieceIndex);
                                    } else if (!pieceDone[pieceIndex]) {
                                        permanentlyFailed.incrementAndGet();
                                    }
                                }
                                if (err != null) {
                                    // 连接类错误通常会影响后续分片，直接结束该 Peer
                                    throw new IOException(err);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // 单个 Peer 失败不影响整体，队列中的分片由其他 Peer 接管
                    } finally {
                        if (pc != null) {
                            pc.close();
                            activePeers.remove(pc);
                        }
                        peerLimit.release();
                    }
                });
            }

            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.MINUTES);

            // 完整性校验：只有全部 piece 都校验通过才允许报告完成，
            // 避免 Peer 全部失败/校验不通过时产生 0 字节"假完成"
            if (!cancelled) {
                int doneCount = 0;
                synchronized (pieceDone) {
                    for (boolean b : pieceDone) {
                        if (b) doneCount++;
                    }
                }
                if (doneCount < pieceCount) {
                    throw new IOException(
                            "下载不完整（" + doneCount + "/" + pieceCount + " 个分块），请稍后重试");
                }
            }
        } finally {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdownNow();
            }
        }

        if (!cancelled) {
            callback.onComplete();
            try { queryTracker(meta, peerId, "completed"); }
            catch (Exception ignored) {}
        }
    }

    // --- Tracker 通信 ---

    private List<InetSocketAddress> queryTracker(
            TorrentMeta meta, byte[] peerId, String event) throws Exception {
        List<InetSocketAddress> all = new ArrayList<>();
        for (String trackerUrl : meta.announceUrls) {
            if (trackerUrl.startsWith("http")) {
                try {
                    List<InetSocketAddress> got =
                            queryHttpTracker(trackerUrl, meta, peerId, event);
                    all.addAll(got);
                    // 起始查询时任一 tracker 给出 peer 即可开始；结束上报不关心结果
                    if ("started".equals(event) && !got.isEmpty()) {
                        return all;
                    }
                } catch (Exception ignored) {}
            }
            // UDP tracker 暂不支持
        }
        return all;
    }

    private List<InetSocketAddress> queryHttpTracker(
            String trackerUrl, TorrentMeta meta, byte[] peerId, String event)
            throws Exception {

        String encodedInfoHash = URLEncoder.encode(
                new String(meta.infoHash, StandardCharsets.ISO_8859_1),
                StandardCharsets.ISO_8859_1);

        String url = trackerUrl
                + (trackerUrl.contains("?") ? "&" : "?")
                + "info_hash=" + encodedInfoHash
                + "&peer_id=" + URLEncoder.encode(
                        new String(peerId, StandardCharsets.ISO_8859_1),
                        StandardCharsets.ISO_8859_1)
                + "&port=6881&uploaded=0&downloaded=0&left="
                + meta.totalLength + "&compact=1&event=" + event;

        byte[] response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .body();

        return parseTrackerResponse(response);
    }

    /** 用 BencodeParser 正确解析 tracker 响应，支持 compact 字节串与字典列表两种 peers 形式 */
    private List<InetSocketAddress> parseTrackerResponse(byte[] data) {
        List<InetSocketAddress> peers = new ArrayList<>();
        Map<String, Object> resp = new BencodeParser(data).readDict();

        Object failure = resp.get("failure reason");
        if (failure instanceof byte[] b) {
            throw new BencodeParser.BencodeException(
                    "Tracker 拒绝: " + BencodeParser.asText(b));
        }

        Object peersObj = resp.get("peers");
        if (peersObj instanceof byte[] b) {
            // compact 模式：每 6 字节 = 4 字节 IP + 2 字节端口
            for (int i = 0; i + 6 <= b.length; i += 6) {
                String ip = (b[i] & 0xFF) + "."
                        + (b[i + 1] & 0xFF) + "."
                        + (b[i + 2] & 0xFF) + "."
                        + (b[i + 3] & 0xFF);
                int port = ((b[i + 4] & 0xFF) << 8) | (b[i + 5] & 0xFF);
                if (port > 0) {
                    peers.add(new InetSocketAddress(ip, port));
                }
            }
        } else if (peersObj instanceof List<?> list) {
            // 字典模式：{ip:..., port:...}
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String ip = BencodeParser.asText(m.get("ip"));
                Object portObj = m.get("port");
                if (ip != null && portObj instanceof Long port && port > 0) {
                    peers.add(new InetSocketAddress(ip, port.intValue()));
                }
            }
        }
        return peers;
    }

    // --- 种子文件解析 ---

    private TorrentMeta parseTorrentFile(String path) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(path));
        BencodeParser parser = new BencodeParser(data);
        Map<String, Object> dict = parser.readDict();
        return buildMeta(dict, parser);
    }

    private TorrentMeta parseMagnetLink(String magnet) throws Exception {
        TorrentMeta meta = new TorrentMeta();
        URI uri = new URI(magnet);
        String query = uri.getSchemeSpecificPart();

        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                switch (kv[0]) {
                    case "xt":
                        if (kv[1].startsWith("urn:btih:")) {
                            String hash = kv[1].substring(9);
                            // 仅支持 40 位十六进制 info hash（base32 形式需要额外解码）
                            if (hash.length() != 40) {
                                throw new IOException(
                                        "暂不支持的磁力链接 info hash（仅支持 40 位十六进制），请改用 .torrent 文件");
                            }
                            meta.infoHash = hexToBytes(hash);
                        }
                        break;
                    case "dn":
                        meta.name = sanitizeName(URLDecoder.decode(kv[1],
                                StandardCharsets.UTF_8));
                        break;
                    case "tr":
                        meta.announceUrls.add(URLDecoder.decode(kv[1],
                                StandardCharsets.UTF_8));
                        break;
                }
            }
        }
        if (meta.name == null) meta.name = "magnet_download";
        return meta;
    }

    private TorrentMeta buildMeta(Map<String, Object> dict,
                                   BencodeParser parser) {
        TorrentMeta meta = new TorrentMeta();

        Object announce = dict.get("announce");
        if (announce instanceof byte[] b) {
            meta.announceUrls.add(BencodeParser.asText(b));
        }

        Object announceList = dict.get("announce-list");
        if (announceList instanceof List<?> tiers) {
            for (Object tier : tiers) {
                if (tier instanceof List<?> urls) {
                    for (Object url : urls) {
                        String s = BencodeParser.asText(url);
                        if (s != null) {
                            meta.announceUrls.add(s);
                        }
                    }
                }
            }
        }

        Object infoObj = dict.get("info");
        if (infoObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> info = (Map<String, Object>) infoObj;

            Object name = info.get("name");
            if (name instanceof byte[] b) {
                meta.name = sanitizeName(BencodeParser.asText(b));
            }

            Object pieceLength = info.get("piece length");
            if (pieceLength instanceof Long l) {
                meta.pieceLength = l;
            }

            // 多文件种子：累加各文件长度并记录路径与全局偏移
            Object files = info.get("files");
            if (files instanceof List<?> fileList && !fileList.isEmpty()) {
                long offset = 0;
                int idx = 0;
                for (Object f : fileList) {
                    if (!(f instanceof Map<?, ?> fm)) continue;
                    FileEntry entry = new FileEntry();
                    Object fl = fm.get("length");
                    if (fl instanceof Long l) {
                        entry.length = l;
                    }
                    Object p = fm.get("path");
                    if (p instanceof List<?> pathList) {
                        for (Object seg : pathList) {
                            String s = sanitizeName(BencodeParser.asText(seg));
                            if (s != null && !s.isEmpty() && !".".equals(s)
                                    && !"..".equals(s)) {
                                entry.path.add(s);
                            }
                        }
                    }
                    if (entry.path.isEmpty()) {
                        entry.path.add("file_" + idx);
                    }
                    entry.offset = offset;
                    meta.files.add(entry);
                    offset += entry.length;
                    idx++;
                }
                meta.totalLength = offset;
            } else {
                Object length = info.get("length");
                if (length instanceof Long l) {
                    meta.totalLength = l;
                }
            }

            // pieces 是二进制字节串（每 20 字节一个 SHA-1），
            // 解析器返回 byte[] 后这里才能正确切分
            Object pieces = info.get("pieces");
            if (pieces instanceof byte[] p) {
                for (int i = 0; i + 20 <= p.length; i += 20) {
                    meta.pieceHashes.add(Arrays.copyOfRange(p, i, i + 20));
                }
            }

            byte[] infoBytes = parser.getInfoBytes();
            if (infoBytes.length > 0) {
                try {
                    meta.infoHash = sha1(infoBytes);
                } catch (NoSuchAlgorithmException e) {
                    // SHA-1 属于 Java 平台必备算法，缺失说明运行环境异常
                    throw new IllegalStateException("当前运行环境不支持 SHA-1，无法计算 info hash", e);
                }
            }
        }

        return meta;
    }

    /** 去掉/替换文件名中的路径分隔符与控制字符，防止越权写路径 */
    private static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String s = raw.replace('\\', '_').replace('/', '_')
                .replace("..", "_");
        s = s.replaceAll("[\\x00-\\x1f]", "_").trim();
        return s.isEmpty() ? "unknown" : s;
    }

    // --- 文件落盘 ---

    private FileSink createFileSink(TorrentMeta meta, String savePath)
            throws IOException {
        if (meta.files.size() > 1) {
            return new MultiFileSink(savePath, meta);
        }
        return new SingleFileSink(savePath, meta);
    }

    /** 分片数据落盘抽象：按种子全局偏移写入，允许跨文件边界 */
    private interface FileSink extends Closeable {
        void write(long globalOffset, byte[] data, int off, int len)
                throws IOException;
    }

    private static final class SingleFileSink implements FileSink {
        private final RandomAccessFile raf;

        SingleFileSink(String savePath, TorrentMeta meta) throws IOException {
            File saveFile = FileUtils.buildSaveFile(savePath, meta.name);
            this.raf = new RandomAccessFile(saveFile, "rw");
            raf.setLength(meta.totalLength);
        }

        @Override
        public void write(long globalOffset, byte[] data, int off, int len)
                throws IOException {
            raf.seek(globalOffset);
            raf.write(data, off, len);
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    private static final class MultiFileSink implements FileSink {
        private final List<RandomAccessFile> channels;
        private final TorrentMeta meta;

        MultiFileSink(String savePath, TorrentMeta meta) throws IOException {
            this.meta = meta;
            this.channels = new ArrayList<>(meta.files.size());
            File baseDir = new File(savePath, meta.name);
            if (baseDir.exists() && !baseDir.isDirectory()) {
                // 同名文件占用目录名时加后缀避让
                baseDir = new File(savePath, meta.name + "_bt");
            }
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                throw new IOException("无法创建下载目录: " + baseDir.getAbsolutePath());
            }
            for (FileEntry entry : meta.files) {
                File f = baseDir.toPath().resolve(
                        String.join(File.separator, entry.path)).toFile();
                File parent = f.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录: " + parent.getAbsolutePath());
                }
                RandomAccessFile raf = new RandomAccessFile(f, "rw");
                raf.setLength(entry.length);
                channels.add(raf);
            }
        }

        @Override
        public void write(long globalOffset, byte[] data, int off, int len)
                throws IOException {
            int written = 0;
            while (written < len) {
                long absPos = globalOffset + written;
                // 定位全局偏移所属文件（文件数通常不多，逆序/顺序查找均可）
                int idx = 0;
                for (int i = 0; i < meta.files.size(); i++) {
                    FileEntry e = meta.files.get(i);
                    if (absPos >= e.offset && absPos < e.offset + e.length) {
                        idx = i;
                        break;
                    }
                    if (i == meta.files.size() - 1) {
                        idx = i;
                    }
                }
                FileEntry e = meta.files.get(idx);
                long inFilePos = absPos - e.offset;
                int chunk = (int) Math.min(len - written, e.length - inFilePos);
                RandomAccessFile raf = channels.get(idx);
                raf.seek(inFilePos);
                raf.write(data, off + written, chunk);
                written += chunk;
            }
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (RandomAccessFile raf : channels) {
                try { raf.close(); }
                catch (IOException ex) { if (first == null) first = ex; }
            }
            if (first != null) throw first;
        }
    }

    // --- 工具方法 ---

    private boolean verifyPiece(byte[] data, byte[] expectedHash) {
        try {
            return Arrays.equals(sha1(data), expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] sha1(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    private static byte[] generatePeerId() {
        byte[] id = new byte[20];
        new SecureRandom().nextBytes(id);
        byte[] prefix = "-MPD0200-".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, id, 0, Math.min(prefix.length, 20));
        return id;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("非法十六进制 info hash: " + hex);
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(
                    hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    @Override
    public void cancel() {
        cancelled = true;
        if (executor != null) executor.shutdownNow();
        synchronized (activePeers) {
            for (PeerConnection pc : activePeers) pc.close();
        }
    }

    @Override
    public void close() {
        if (executor != null && !executor.isShutdown()) executor.shutdown();
        synchronized (activePeers) {
            for (PeerConnection pc : activePeers) pc.close();
        }
    }

    // --- 内部类 ---

    static class TorrentMeta {
        String name = "unknown";
        long totalLength = 0;
        long pieceLength = DEFAULT_PIECE_LENGTH;
        byte[] infoHash;
        List<byte[]> pieceHashes = new ArrayList<>();
        List<String> announceUrls = new ArrayList<>();
        /** 多文件种子的文件列表（size>1 时生效），offset 为该文件在全量数据中的起始偏移 */
        List<FileEntry> files = new ArrayList<>();

        int pieceLength(int index, int count, long total) {
            if (index == count - 1) {
                long last = total - (long) index * pieceLength;
                return (int) Math.max(0, last);
            }
            return (int) pieceLength;
        }
    }

    static class FileEntry {
        final List<String> path = new ArrayList<>();
        long length;
        long offset;
    }

    /**
     * Peer Wire Protocol 连接。
     * 握手后持续维护 choke/unchoke 状态与对端位图，request 只在 unchoke 时发出，
     * 任何控制类消息都不会再被误判为 piece 数据。
     */
    static class PeerConnection {

        private final InetSocketAddress addr;
        private final TorrentMeta meta;
        private final byte[] peerId;
        private final int pieceCount;
        private Socket socket;
        private InputStream in;
        private OutputStream out;

        /** 对端是否 choke 本端（握手后按协议默认为 true） */
        private volatile boolean chokingMe = true;
        /** 对端持有的分片位图；null 表示未知（对端尚未发 bitfield） */
        volatile BitSet availablePieces;

        PeerConnection(InetSocketAddress addr, TorrentMeta meta,
                       byte[] peerId, int pieceCount) {
            this.addr = addr;
            this.meta = meta;
            this.peerId = peerId;
            this.pieceCount = pieceCount;
        }

        void connect() throws Exception {
            socket = new Socket();
            socket.connect(addr, 5000);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            in = socket.getInputStream();
            out = socket.getOutputStream();

            // 握手
            byte[] handshake = new byte[68];
            handshake[0] = 19;
            System.arraycopy(
                    "BitTorrent protocol".getBytes(StandardCharsets.UTF_8),
                    0, handshake, 1, 19);
            System.arraycopy(meta.infoHash, 0, handshake, 28, 20);
            System.arraycopy(peerId, 0, handshake, 48, 20);
            out.write(handshake);
            out.flush();

            byte[] response = new byte[68];
            readFully(response);

            byte[] respHash = Arrays.copyOfRange(response, 28, 48);
            if (!Arrays.equals(meta.infoHash, respHash)) {
                throw new IOException("Info hash 不匹配");
            }

            sendMessage(2, new byte[0]); // interested

            // 排空初始消息（bitfield/choke/unchoke），尽量等到 unchoke；
            // 超时也允许进入下载，downloadPiece 内会继续等待
            try {
                awaitUnchoked(HANDSHAKE_TIMEOUT_MS);
            } catch (SocketTimeoutException ignored) {
            }
        }

        /**
         * 下载完整分片。持续读取并分发控制消息，直到收齐所有块；
         * 被 choke 时等待 unchoke 并重发当前块。
         */
        byte[] downloadPiece(int pieceIndex, int pieceLen) throws Exception {
            if (availablePieces != null && !availablePieces.get(pieceIndex)) {
                throw new IOException("Peer 不持有分片 " + pieceIndex);
            }
            byte[] piece = new byte[pieceLen];
            int offset = 0;
            boolean requestInFlight = false;
            long deadline = System.currentTimeMillis() + PIECE_TIMEOUT_MS;

            while (offset < pieceLen) {
                long waitMs = deadline - System.currentTimeMillis();
                if (waitMs <= 0) {
                    throw new SocketTimeoutException(
                            "分片 " + pieceIndex + " 下载超时");
                }
                if (chokingMe) {
                    if (!awaitUnchoked(waitMs)) {
                        throw new IOException("Peer 长时间 choke，放弃分片 " + pieceIndex);
                    }
                }
                int blockSize = (int) Math.min(BLOCK_SIZE, (long) pieceLen - offset);
                if (!requestInFlight) {
                    byte[] payload = new byte[12];
                    putInt(payload, 0, pieceIndex);
                    putInt(payload, 4, offset);
                    putInt(payload, 8, blockSize);
                    sendMessage(6, payload); // request
                    requestInFlight = true;
                }

                PeerMessage msg = readMessage(waitMs);
                if (msg == null) {
                    continue; // keep-alive
                }
                switch (msg.id) {
                    case 0 -> { // choke：在途请求作废，等待 unchoke 后重发
                        chokingMe = true;
                        requestInFlight = false;
                    }
                    case 1 -> { // unchoke：重发可能被丢弃的当前块
                        chokingMe = false;
                        requestInFlight = false;
                    }
                    case 4 -> handleHave(msg.payload);
                    case 5 -> handleBitfield(msg.payload);
                    case 7 -> { // piece
                        if (msg.payload.length < 8) {
                            throw new IOException("piece 消息过短");
                        }
                        int idx = getInt(msg.payload, 0);
                        int begin = getInt(msg.payload, 4);
                        if (idx != pieceIndex) {
                            break; // 不属于当前分片，丢弃
                        }
                        if (begin < offset) {
                            break; // 已收过的重复块，忽略
                        }
                        if (begin != offset
                                || begin + (msg.payload.length - 8) > pieceLen) {
                            throw new IOException(
                                    "piece 块乱序/越界: index=" + idx
                                            + ", begin=" + begin + ", offset=" + offset);
                        }
                        System.arraycopy(msg.payload, 8, piece, offset,
                                msg.payload.length - 8);
                        offset += msg.payload.length - 8;
                        requestInFlight = false;
                    }
                    default -> {
                        // interested/not-interested/其他扩展消息忽略
                    }
                }
            }
            return piece;
        }

        /** 处理控制消息直到对端 unchoke；超时返回 false */
        private boolean awaitUnchoked(long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
            while (chokingMe) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                PeerMessage msg = readMessage(left);
                if (msg == null) {
                    continue;
                }
                switch (msg.id) {
                    case 0 -> chokingMe = true;
                    case 1 -> chokingMe = false;
                    case 4 -> handleHave(msg.payload);
                    case 5 -> handleBitfield(msg.payload);
                    default -> { }
                }
            }
            return true;
        }

        private void handleHave(byte[] payload) {
            if (payload.length >= 4) {
                int i = getInt(payload, 0);
                if (i >= 0 && i < pieceCount) {
                    if (availablePieces == null) {
                        availablePieces = new BitSet(pieceCount);
                    }
                    availablePieces.set(i);
                }
            }
        }

        private void handleBitfield(byte[] payload) {
            BitSet bitSet = new BitSet(pieceCount);
            for (int i = 0; i < pieceCount && i / 8 < payload.length; i++) {
                if ((payload[i / 8] & (0x80 >> (i % 8))) != 0) {
                    bitSet.set(i);
                }
            }
            availablePieces = bitSet;
        }

        void sendMessage(int id, byte[] payload) throws IOException {
            int len = 1 + payload.length;
            byte[] header = new byte[4];
            putInt(header, 0, len);
            out.write(header);
            out.write(id);
            out.write(payload);
            out.flush();
        }

        /** 读一条消息，读超时不超过 maxWaitMs（适配分片整体截止时间） */
        PeerMessage readMessage(long maxWaitMs) throws Exception {
            int timeout = (int) Math.max(1, Math.min(15_000, maxWaitMs));
            socket.setSoTimeout(timeout);
            byte[] lenBytes = new byte[4];
            readFully(lenBytes);
            int len = getInt(lenBytes, 0);
            if (len < 0 || len > 8 * 1024 * 1024) {
                throw new IOException("非法消息长度: " + len);
            }
            if (len == 0) {
                return null; // keep-alive
            }

            int id = in.read();
            if (id < 0) {
                throw new IOException("Connection closed");
            }
            byte[] payload = new byte[len - 1];
            if (payload.length > 0) readFully(payload);

            PeerMessage msg = new PeerMessage();
            msg.id = id;
            msg.payload = payload;
            return msg;
        }

        private void readFully(byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int read = in.read(buf, off, buf.length - off);
                if (read < 0) throw new IOException("Connection closed");
                off += read;
            }
        }

        private static void putInt(byte[] buf, int offset, int value) {
            buf[offset]     = (byte) (value >>> 24);
            buf[offset + 1] = (byte) (value >>> 16);
            buf[offset + 2] = (byte) (value >>> 8);
            buf[offset + 3] = (byte) value;
        }

        private static int getInt(byte[] buf, int offset) {
            return ((buf[offset] & 0xFF) << 24)
                    | ((buf[offset + 1] & 0xFF) << 16)
                    | ((buf[offset + 2] & 0xFF) << 8)
                    | (buf[offset + 3] & 0xFF);
        }

        void close() {
            FileUtils.closeQuietly(socket);
        }

        static class PeerMessage {
            int id;
            byte[] payload;
        }
    }
}
