import Darwin
import Foundation

/// Blocking UDP JSON-RPC to WiZ bulbs on port 38899 (see PROTOCOL.md §2). Call off the main thread.
enum WizUDP {
    static let port: UInt16 = 38899

    struct Reply {
        let ip: String
        let json: [String: Any]
    }

    /// Broadcasts `message` and collects every reply until `timeout`.
    static func broadcast(_ message: [String: Any], timeout: TimeInterval = 2) -> [Reply] {
        exchange(message, host: "255.255.255.255", broadcast: true, timeout: timeout, attempts: 2, firstOnly: false)
    }

    /// Sends `message` to one bulb, retrying until the first reply.
    static func request(_ message: [String: Any], ip: String, timeout: TimeInterval = 1.5) -> [String: Any]? {
        exchange(message, host: ip, broadcast: false, timeout: timeout, attempts: 3, firstOnly: true).first?.json
    }

    private static func exchange(_ message: [String: Any], host: String, broadcast: Bool,
                                 timeout: TimeInterval, attempts: Int, firstOnly: Bool) -> [Reply] {
        guard let payload = try? JSONSerialization.data(withJSONObject: message) else { return [] }
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return [] }
        defer { close(fd) }

        var yes: Int32 = 1
        if broadcast {
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
        }
        var tv = timeval(tv_sec: 0, tv_usec: 150_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        guard inet_pton(AF_INET, host, &addr.sin_addr) == 1 else { return [] }

        func sendOnce() {
            _ = payload.withUnsafeBytes { buf in
                withUnsafePointer(to: &addr) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(fd, buf.baseAddress, buf.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }

        var replies: [String: Reply] = [:]
        var buffer = [UInt8](repeating: 0, count: 4096)
        let start = Date()
        let resendEvery = timeout / Double(attempts)
        var sent = 0
        while Date().timeIntervalSince(start) < timeout {
            if sent < attempts && Date().timeIntervalSince(start) >= Double(sent) * resendEvery {
                sendOnce()
                sent += 1
            }
            var from = sockaddr_in()
            var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(fd, &buffer, buffer.count, 0, $0, &fromLen)
                }
            }
            guard n > 0 else { continue }
            var ipBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &from.sin_addr, &ipBuf, socklen_t(INET_ADDRSTRLEN))
            let ip = String(cString: ipBuf)
            guard let json = try? JSONSerialization.jsonObject(with: Data(buffer[0..<n])) as? [String: Any] else { continue }
            replies[ip] = Reply(ip: ip, json: json)
            if firstOnly { break }
        }
        return Array(replies.values)
    }
}
