import Foundation
import Network

/// Minimal MQTT 3.1.1 client over TLS: CONNECT, SUBSCRIBE, PUBLISH (QoS 0), PINGREQ, auto-reconnect.
/// All state is confined to `queue`; callbacks are delivered on the main queue.
final class MQTTClient: @unchecked Sendable {
    let host: String
    let port: UInt16
    let clientId: String
    let keepAlive: UInt16 = 30

    var onMessage: ((String, Data) -> Void)?
    var onConnectionChange: ((Bool) -> Void)?
    /// Called when the broker rejects credentials (CONNACK 4/5). Reconnect is paused until `connect` is called again.
    var onAuthFailure: (() -> Void)?

    private let queue = DispatchQueue(label: "in.canvs.homebar.mqtt")
    private var username = ""
    private var password = ""
    private var connection: NWConnection?
    private var buffer = [UInt8]()
    private var subscriptions = Set<String>()
    private var nextPacketId: UInt16 = 1
    private var pingTimer: DispatchSourceTimer?
    private var retryDelay: Double = 1
    private var stopped = true
    private var connected = false

    init(host: String, port: UInt16, clientId: String) {
        self.host = host
        self.port = port
        self.clientId = clientId
    }

    func connect(username: String, password: String) {
        queue.async {
            self.username = username
            self.password = password
            self.stopped = false
            self.teardown()
            self.open()
        }
    }

    func disconnect() {
        queue.async {
            self.stopped = true
            if self.connected { self.send([0xE0, 0x00]) }
            self.teardown()
        }
    }

    func subscribe(_ topic: String) {
        queue.async {
            guard self.subscriptions.insert(topic).inserted, self.connected else { return }
            self.sendSubscribe([topic])
        }
    }

    func publish(_ topic: String, _ payload: Data) {
        queue.async {
            guard self.connected else { return }
            let body = Self.string(topic) + [UInt8](payload)
            self.send([0x30] + Self.remainingLength(body.count) + body)
        }
    }

    // MARK: Connection lifecycle

    private func open() {
        let params = NWParameters(tls: NWProtocolTLS.Options(), tcp: NWProtocolTCP.Options())
        let conn = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: params)
        connection = conn
        buffer.removeAll()
        conn.stateUpdateHandler = { [weak self, weak conn] state in
            guard let self, let conn, conn === self.connection else { return }
            switch state {
            case .ready:
                self.sendConnect()
                self.receive(on: conn)
            case .failed, .cancelled:
                self.connectionLost()
            case .waiting:
                self.connectionLost()
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    private func teardown() {
        pingTimer?.cancel()
        pingTimer = nil
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        setConnected(false)
    }

    private func connectionLost() {
        teardown()
        guard !stopped else { return }
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, 64)
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, !self.stopped, self.connection == nil else { return }
            self.open()
        }
    }

    private func setConnected(_ value: Bool) {
        guard connected != value else { return }
        connected = value
        let cb = onConnectionChange
        DispatchQueue.main.async { cb?(value) }
    }

    // MARK: Packets out

    private func sendConnect() {
        var vh: [UInt8] = Self.string("MQTT") + [0x04]
        vh.append(0b1100_0010) // username, password, clean session
        vh += [UInt8(keepAlive >> 8), UInt8(keepAlive & 0xFF)]
        let payload = Self.string(clientId) + Self.string(username) + Self.string(password)
        let body = vh + payload
        send([0x10] + Self.remainingLength(body.count) + body)
    }

    private func sendSubscribe(_ topics: [String]) {
        let id = nextPacketId
        nextPacketId = nextPacketId &+ 1 == 0 ? 1 : nextPacketId &+ 1
        var body: [UInt8] = [UInt8(id >> 8), UInt8(id & 0xFF)]
        for t in topics { body += Self.string(t) + [0x00] }
        send([0x82] + Self.remainingLength(body.count) + body)
    }

    private func startPing() {
        pingTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        let interval = Double(keepAlive) * 0.8
        timer.schedule(deadline: .now() + interval, repeating: interval)
        timer.setEventHandler { [weak self] in self?.send([0xC0, 0x00]) }
        timer.resume()
        pingTimer = timer
    }

    private func send(_ bytes: [UInt8]) {
        connection?.send(content: Data(bytes), completion: .contentProcessed { [weak self] error in
            if error != nil { self?.connectionLost() }
        })
    }

    // MARK: Packets in

    private func receive(on conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, conn === self.connection else { return }
            if let data { self.buffer += data; self.parseBuffer() }
            if error != nil || isComplete {
                self.connectionLost()
            } else if conn === self.connection {
                self.receive(on: conn)
            }
        }
    }

    private func parseBuffer() {
        while buffer.count >= 2 {
            var multiplier = 1, length = 0, index = 1
            var complete = false
            while index < buffer.count && index <= 4 {
                let byte = Int(buffer[index])
                length += (byte & 0x7F) * multiplier
                multiplier *= 128
                index += 1
                if byte & 0x80 == 0 { complete = true; break }
            }
            guard complete, buffer.count >= index + length else { return }
            let header = buffer[0]
            let body = Array(buffer[index..<(index + length)])
            buffer.removeFirst(index + length)
            handle(header: header, body: body)
        }
    }

    private func handle(header: UInt8, body: [UInt8]) {
        switch header >> 4 {
        case 2: // CONNACK
            let rc = body.count > 1 ? body[1] : 0xFF
            if rc == 0 {
                retryDelay = 1
                setConnected(true)
                startPing()
                if !subscriptions.isEmpty { sendSubscribe(Array(subscriptions)) }
            } else {
                stopped = rc == 4 || rc == 5
                teardown()
                if stopped {
                    let cb = onAuthFailure
                    DispatchQueue.main.async { cb?() }
                } else {
                    connectionLost()
                }
            }
        case 3: // PUBLISH
            guard body.count >= 2 else { return }
            let topicLen = Int(body[0]) << 8 | Int(body[1])
            guard body.count >= 2 + topicLen else { return }
            let topic = String(decoding: body[2..<(2 + topicLen)], as: UTF8.self)
            let qos = (header >> 1) & 0x03
            var offset = 2 + topicLen
            if qos > 0 {
                guard body.count >= offset + 2 else { return }
                let pid = Array(body[offset..<(offset + 2)])
                offset += 2
                send([qos == 1 ? 0x40 : 0x50, 0x02] + pid) // PUBACK / PUBREC
            }
            let payload = Data(body[offset...])
            let cb = onMessage
            DispatchQueue.main.async { cb?(topic, payload) }
        default:
            break // SUBACK, PINGRESP, etc.
        }
    }

    // MARK: Encoding helpers

    private static func string(_ s: String) -> [UInt8] {
        let utf8 = Array(s.utf8)
        return [UInt8(utf8.count >> 8), UInt8(utf8.count & 0xFF)] + utf8
    }

    private static func remainingLength(_ n: Int) -> [UInt8] {
        var out = [UInt8]()
        var x = n
        repeat {
            var byte = UInt8(x % 128)
            x /= 128
            if x > 0 { byte |= 0x80 }
            out.append(byte)
        } while x > 0
        return out
    }
}
