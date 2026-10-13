import CommonCrypto
import Darwin
import Foundation

/// Per-device secrets for MirAIe LAN control, fetched once from the cloud (PROTOCOL.md §1.6).
struct LocalACInfo: Codable {
    var mac: String
    var ip: String?
    var key: String // hex
    var iv: String // hex

    var macNoColons: String { mac.replacingOccurrences(of: ":", with: "").uppercased() }
}

/// Encrypted UDP control of MirAIe ACs on port 5001. Socket and state are confined to `queue`;
/// a background thread receives packets. Callbacks are delivered on the main queue.
final class LocalACTransport: @unchecked Sendable {
    static let port: UInt16 = 5001

    /// (deviceId, status JSON)
    var onStatus: ((String, [String: Any]) -> Void)?

    private let queue = DispatchQueue(label: "in.canvs.homebar.localac")
    private var fd: Int32 = -1
    private var devices: [String: LocalACInfo] = [:]
    private var waiters: [String: [CheckedContinuation<String?, Never>]] = [:]
    private(set) var bindError: String?

    init() {
        queue.sync { openSocket() }
    }

    func register(deviceId: String, info: LocalACInfo) {
        queue.async { self.devices[deviceId] = info }
    }

    /// Sends DISC_REQ (unicast to the last known IP, then subnet broadcasts) and waits for DISC_RSP.
    /// Returns the AC's IP, or nil after `timeout`.
    func discover(deviceId: String, timeout: TimeInterval = 3) async -> String? {
        await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
            queue.async {
                guard self.fd >= 0, let info = self.devices[deviceId] else { return cont.resume(returning: nil) }
                self.waiters[deviceId, default: []].append(cont)
                let probe = Data("DISC_REQ_\(info.macNoColons)".utf8)
                if let ip = info.ip { self.sendRaw(probe, to: ip) }
                for addr in Self.broadcastAddresses() { self.sendRaw(probe, to: addr) }
                self.queue.asyncAfter(deadline: .now() + 1) {
                    guard self.waiters[deviceId] != nil else { return }
                    for addr in Self.broadcastAddresses() { self.sendRaw(probe, to: addr) }
                }
                self.queue.asyncAfter(deadline: .now() + timeout) {
                    self.resolveWaiters(deviceId, ip: nil)
                }
            }
        }
    }

    /// Encrypts and sends a control JSON to the device's discovered IP. Returns false if not possible.
    @discardableResult
    func send(deviceId: String, _ json: [String: Any]) -> Bool {
        queue.sync {
            guard self.fd >= 0, let info = self.devices[deviceId], let ip = info.ip,
                  let plain = try? JSONSerialization.data(withJSONObject: json),
                  let cipher = Self.crypt(plain, info: info, encrypt: true) else { return false }
            return self.sendRaw(cipher, to: ip)
        }
    }

    func ip(for deviceId: String) -> String? { queue.sync { devices[deviceId]?.ip } }

    // MARK: Socket

    private func openSocket() {
        let s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard s >= 0 else { bindError = "socket() failed"; return }
        var yes: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(s, SOL_SOCKET, SO_REUSEPORT, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(s, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = Self.port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard rc == 0 else {
            bindError = "Cannot bind UDP port 5001 (errno \(errno))"
            close(s)
            return
        }
        fd = s
        let thread = Thread { [weak self] in self?.receiveLoop(s) }
        thread.name = "HomeBar.LocalAC"
        thread.start()
    }

    private func receiveLoop(_ s: Int32) {
        var buffer = [UInt8](repeating: 0, count: 2048)
        while true {
            var from = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(s, &buffer, buffer.count, 0, $0, &len) }
            }
            if n < 0 {
                if errno == EINTR { continue }
                return
            }
            guard UInt16(bigEndian: from.sin_port) == Self.port, n > 0 else { continue }
            var ipBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &from.sin_addr, &ipBuf, socklen_t(INET_ADDRSTRLEN))
            let ip = String(cString: ipBuf)
            let packet = Data(buffer[0..<n])
            queue.async { self.handle(packet, from: ip) }
        }
    }

    private func handle(_ packet: Data, from ip: String) {
        for (deviceId, info) in devices {
            guard let plain = Self.crypt(packet, info: info, encrypt: false),
                  let text = String(data: plain, encoding: .utf8), !text.isEmpty else { continue }
            if text.caseInsensitiveCompare("DISC_RSP_\(info.macNoColons)") == .orderedSame {
                devices[deviceId]?.ip = ip
                resolveWaiters(deviceId, ip: ip)
                return
            }
            if info.ip == ip, let json = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] {
                let cb = onStatus
                DispatchQueue.main.async { cb?(deviceId, json) }
                return
            }
        }
    }

    private func resolveWaiters(_ deviceId: String, ip: String?) {
        guard let list = waiters.removeValue(forKey: deviceId) else { return }
        list.forEach { $0.resume(returning: ip) }
    }

    @discardableResult
    private func sendRaw(_ data: Data, to host: String) -> Bool {
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = Self.port.bigEndian
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else { return false }
        let sent = data.withUnsafeBytes { buf in
            withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, buf.baseAddress, buf.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        return sent == data.count
    }

    /// Directed broadcast addresses of active IPv4 interfaces, plus the limited broadcast.
    static func broadcastAddresses() -> [String] {
        var result = Set<String>()
        var ifap: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&ifap) == 0, let first = ifap {
            var p: UnsafeMutablePointer<ifaddrs>? = first
            while let ifa = p {
                let flags = Int32(ifa.pointee.ifa_flags)
                if let a = ifa.pointee.ifa_addr, a.pointee.sa_family == sa_family_t(AF_INET),
                   flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0, flags & IFF_BROADCAST != 0,
                   let b = ifa.pointee.ifa_dstaddr {
                    var sin = b.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
                    var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                    inet_ntop(AF_INET, &sin.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN))
                    result.insert(String(cString: buf))
                }
                p = ifa.pointee.ifa_next
            }
            freeifaddrs(first)
        }
        result.insert("255.255.255.255")
        return Array(result)
    }

    // MARK: Crypto (AES-CBC, no padding, zero-filled blocks)

    static func crypt(_ input: Data, info: LocalACInfo, encrypt: Bool) -> Data? {
        guard let key = hexData(info.key), let iv = hexData(info.iv), iv.count == kCCBlockSizeAES128,
              [kCCKeySizeAES128, kCCKeySizeAES192, kCCKeySizeAES256].contains(key.count) else { return nil }
        var data = input
        if encrypt {
            let rem = data.count % kCCBlockSizeAES128
            if rem != 0 { data.append(Data(count: kCCBlockSizeAES128 - rem)) }
        } else if data.count % kCCBlockSizeAES128 != 0 || data.isEmpty {
            return nil
        }
        var out = Data(count: data.count + kCCBlockSizeAES128)
        let outCapacity = out.count
        var moved = 0
        let status = out.withUnsafeMutableBytes { outBuf in
            data.withUnsafeBytes { inBuf in
                key.withUnsafeBytes { keyBuf in
                    iv.withUnsafeBytes { ivBuf in
                        CCCrypt(CCOperation(encrypt ? kCCEncrypt : kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), 0,
                                keyBuf.baseAddress, key.count, ivBuf.baseAddress,
                                inBuf.baseAddress, data.count, outBuf.baseAddress, outCapacity, &moved)
                    }
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        out.count = moved
        if !encrypt {
            while let last = out.last, last == 0 { out.removeLast() }
        }
        return out
    }

    static func hexData(_ hex: String) -> Data? {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var data = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let byte = UInt8(String(bytes: chars[i..<i + 2], encoding: .ascii) ?? "", radix: 16) else { return nil }
            data.append(byte)
            i += 2
        }
        return data
    }
}
