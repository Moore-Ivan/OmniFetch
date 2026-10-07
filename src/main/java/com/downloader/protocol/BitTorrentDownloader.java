package com.downloader.protocol;

import com.downloader.bencode.BencodeParser;
import com.downloader.model.ProgressCallback;
import com.downloader.util.FileUtils;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * BitTorrent / 磁力链接下载器
 * 实现 Peer Wire Protocol
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

    private static final int PIECE_LENGTH = 262_144; // 256 KB
    private static final int MAX_PEERS = 30;
    private static final int BLOCK_SIZE = 16_384;    // 16 KB

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

        // 3. 初始化 Piece 状态
        int pieceCount = meta.pieceHashes.size();
        boolean[] pieceDone = new boolean[pieceCount];

        File saveFile = FileUtils.buildSaveFile(savePath, meta.name);
        RandomAccessFile raf = new RandomAccessFile(saveFile, "rw");
        raf.setLength(meta.totalLength);
        FileChannel channel = raf.getChannel();

        // 4. 连接 Peer 并下载
        executor = Executors.newFixedThreadPool(MAX_PEERS);
        Semaphore peerLimit = new Semaphore(MAX_PEERS);
        AtomicLong downloaded = new AtomicLong(0);
        long startTime = System.currentTimeMillis();
        AtomicLong lastUpdate = new AtomicLong(startTime);
        AtomicLong lastBytes = new AtomicLong(0);

        try {
            for (InetSocketAddress peerAddr : peers) {
                if (cancelled) break;
                if (downloaded.get() >= meta.totalLength) break;

                peerLimit.acquire();
                executor.submit(() -> {
                    PeerConnection pc = null;
                    try {
                        pc = new PeerConnection(peerAddr, meta, peerId);
                        pc.connect();
                        activePeers.add(pc);

                        for (int i = 0; i < pieceCount && !cancelled; i++) {
                            synchronized (pieceDone) {
                                if (pieceDone[i]) continue;
                            }

                            int pLen = meta.pieceLength(i, pieceCount,
                                    meta.totalLength);
                            byte[] data = pc.downloadPiece(i, pLen);

                            if (data != null && verifyPiece(
                                    data, meta.pieceHashes.get(i))) {
                                // 写入位置必须按种子声明的 piece length 计算，
                                // 硬编码值会导致文件内容错位损坏
                                long position = (long) i * meta.pieceLength;
                                // 使用分段内存映射
                                writePieceToFile(channel, position, data);

                                synchronized (pieceDone) {
                                    pieceDone[i] = true;
                                }

                                long dl = downloaded.addAndGet(data.length);
                                long now = System.currentTimeMillis();
                                long lu = lastUpdate.get();
                                if (now - lu >= 300
                                        && lastUpdate.compareAndSet(lu, now)) {
                                    long speed = (long) ((dl - lastBytes.get())
                                            * 1000.0 / (now - lu));
                                    callback.onProgress(dl, meta.totalLength,
                                            speed);
                                    lastBytes.set(dl);
                                }
                            }
                        }
                    } catch (Exception ignored) {
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
            // 关闭资源
            FileUtils.closeQuietly(channel);
            FileUtils.closeQuietly(raf);
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
        for (String trackerUrl : meta.announceUrls) {
            if (trackerUrl.startsWith("http")) {
                try {
                    return queryHttpTracker(trackerUrl, meta, peerId, event);
                } catch (Exception ignored) {}
            }
        }
        return Collections.emptyList();
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

        return parseCompactPeerList(response);
    }

    private List<InetSocketAddress> parseCompactPeerList(byte[] data)
            throws Exception {
        List<InetSocketAddress> peers = new ArrayList<>();
        String str = new String(data, StandardCharsets.ISO_8859_1);

        int peersIdx = str.indexOf("5:peers");
        if (peersIdx < 0) return peers;

        int colonIdx = str.indexOf(':', peersIdx + 7);
        if (colonIdx < 0) return peers;

        int len = Integer.parseInt(str.substring(peersIdx + 7, colonIdx));
        byte[] peerBytes = Arrays.copyOfRange(data,
                colonIdx + 1, colonIdx + 1 + len);

        for (int i = 0; i + 6 <= peerBytes.length; i += 6) {
            String ip = (peerBytes[i] & 0xFF) + "."
                    + (peerBytes[i + 1] & 0xFF) + "."
                    + (peerBytes[i + 2] & 0xFF) + "."
                    + (peerBytes[i + 3] & 0xFF);
            int port = ((peerBytes[i + 4] & 0xFF) << 8)
                    | (peerBytes[i + 5] & 0xFF);
            if (port > 0) {
                peers.add(new InetSocketAddress(ip, port));
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
                            meta.infoHash = hexToBytes(kv[1].substring(9));
                        }
                        break;
                    case "dn":
                        meta.name = URLDecoder.decode(kv[1],
                                StandardCharsets.UTF_8);
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
                                   BencodeParser parser) throws Exception {
        TorrentMeta meta = new TorrentMeta();

        Object announce = dict.get("announce");
        if (announce instanceof String) {
            meta.announceUrls.add((String) announce);
        }

        Object announceList = dict.get("announce-list");
        if (announceList instanceof List) {
            for (Object tier : (List<?>) announceList) {
                if (tier instanceof List) {
                    for (Object url : (List<?>) tier) {
                        if (url instanceof String) {
                            meta.announceUrls.add((String) url);
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
            if (name instanceof String) meta.name = (String) name;

            Object length = info.get("length");
            if (length instanceof Long) meta.totalLength = (Long) length;

            Object files = info.get("files");
            if (files instanceof List) {
                long total = 0;
                for (Object f : (List<?>) files) {
                    if (f instanceof Map) {
                        Object fl = ((Map<?, ?>) f).get("length");
                        if (fl instanceof Long) total += (Long) fl;
                    }
                }
                meta.totalLength = total;
            }

            Object pieceLength = info.get("piece length");
            if (pieceLength instanceof Long) {
                meta.pieceLength = (Long) pieceLength;
            }

            Object pieces = info.get("pieces");
            if (pieces instanceof byte[]) {
                byte[] p = (byte[]) pieces;
                for (int i = 0; i + 20 <= p.length; i += 20) {
                    meta.pieceHashes.add(Arrays.copyOfRange(p, i, i + 20));
                }
            }

            byte[] infoBytes = parser.getInfoBytes();
            if (infoBytes.length > 0) {
                meta.infoHash = sha1(infoBytes);
            }
        }

        return meta;
    }

    // --- 工具方法 ---

    private boolean verifyPiece(byte[] data, byte[] expectedHash)
            throws Exception {
        return Arrays.equals(sha1(data), expectedHash);
    }

    private static byte[] sha1(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    private static byte[] generatePeerId() {
        byte[] id = new byte[20];
        new Random().nextBytes(id);
        byte[] prefix = "-MPD0200-".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, id, 0, Math.min(prefix.length, 20));
        return id;
    }

    private static byte[] hexToBytes(String hex) {
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
        for (PeerConnection pc : activePeers) pc.close();
    }

    @Override
    public void close() {
        if (executor != null && !executor.isShutdown()) executor.shutdown();
        for (PeerConnection pc : activePeers) pc.close();
    }
    
    /**
     * 使用分段内存映射写入数据，避免一次性映射大文件导致 OOM
     */
    private void writePieceToFile(FileChannel channel, long position, byte[] data) throws Exception {
        int offset = 0;
        int remaining = data.length;
        final long MAX_MAPPING_SIZE = 1024 * 1024 * 100; // 100MB 最大映射大小
        
        while (remaining > 0) {
            long mappingSize = Math.min(MAX_MAPPING_SIZE, remaining);
            MappedByteBuffer buffer = channel.map(
                    FileChannel.MapMode.READ_WRITE, position + offset, mappingSize);
            
            try {
                int bytesToWrite = (int) Math.min(mappingSize, remaining);
                buffer.put(data, offset, bytesToWrite);
                buffer.force(); // 强制刷新到磁盘
                offset += bytesToWrite;
                remaining -= bytesToWrite;
            } finally {
                // 尝试释放内存映射
                cleanBuffer(buffer);
            }
        }
    }
    
    /**
     * 尝试释放内存映射缓冲区
     */
    private void cleanBuffer(MappedByteBuffer buffer) {
        if (buffer != null) {
            try {
                // 通过反射调用 cleaner 方法释放内存映射
                java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                cleanerMethod.setAccessible(true);
                Object cleaner = cleanerMethod.invoke(buffer);
                java.lang.reflect.Method cleanMethod = cleaner.getClass().getMethod("clean");
                cleanMethod.invoke(cleaner);
            } catch (Exception e) {
                // 忽略异常，继续执行
            }
        }
    }

    // --- 内部类 ---

    static class TorrentMeta {
        String name = "unknown";
        long totalLength = 0;
        long pieceLength = PIECE_LENGTH;
        byte[] infoHash;
        List<byte[]> pieceHashes = new ArrayList<>();
        List<String> announceUrls = new ArrayList<>();

        int pieceLength(int index, int count, long total) {
            if (index == count - 1) {
                return (int) (total - (long) index * pieceLength);
            }
            return (int) pieceLength;
        }
    }

    /**
     * Peer Wire Protocol 连接
     */
    static class PeerConnection {

        private final InetSocketAddress addr;
        private final TorrentMeta meta;
        private final byte[] peerId;
        private Socket socket;
        private InputStream in;
        private OutputStream out;

        PeerConnection(InetSocketAddress addr, TorrentMeta meta,
                       byte[] peerId) {
            this.addr = addr;
            this.meta = meta;
            this.peerId = peerId;
        }

        void connect() throws Exception {
            socket = new Socket();
            socket.connect(addr, 5000);
            socket.setSoTimeout(15000);
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
        }

        byte[] downloadPiece(int pieceIndex, int pieceLen) throws Exception {
            byte[] piece = new byte[pieceLen];
            int offset = 0;

            while (offset < pieceLen) {
                int blockSize = Math.min(BLOCK_SIZE, pieceLen - offset);

                byte[] payload = new byte[12];
                putInt(payload, 0, pieceIndex);
                putInt(payload, 4, offset);
                putInt(payload, 8, blockSize);
                sendMessage(6, payload); // request

                PeerMessage msg = readMessage();
                if (msg == null || msg.id != 7) return null; // piece

                int idx = getInt(msg.payload, 0);
                int begin = getInt(msg.payload, 4);
                byte[] block = Arrays.copyOfRange(msg.payload, 8,
                        msg.payload.length);

                if (idx == pieceIndex && begin == offset) {
                    System.arraycopy(block, 0, piece, offset, block.length);
                    offset += block.length;
                } else {
                    return null;
                }
            }
            return piece;
        }

        void sendMessage(int id, byte[] payload) throws Exception {
            int len = 1 + payload.length;
            byte[] header = new byte[4];
            putInt(header, 0, len);
            out.write(header);
            out.write(id);
            out.write(payload);
            out.flush();
        }

        PeerMessage readMessage() throws Exception {
            byte[] lenBytes = new byte[4];
            readFully(lenBytes);
            int len = getInt(lenBytes, 0);
            if (len == 0) return null; // keep-alive

            int id = in.read();
            byte[] payload = new byte[len - 1];
            if (payload.length > 0) readFully(payload);

            PeerMessage msg = new PeerMessage();
            msg.id = id;
            msg.payload = payload;
            return msg;
        }

        private void readFully(byte[] buf) throws Exception {
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
