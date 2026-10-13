import Foundation

/// One capability a MirAIe AC model supports, parsed from the device model's
/// `controlCapabilities` / `statusCapabilitiesV2` (shapes from `capabilities/attributes/*`).
struct ACCapability: Identifiable, Equatable {
    enum Kind: Equatable {
        case toggle                    // "on" / "off"
        case choice([String])          // string values (DisplayControl)
        case intChoice([IntOption])    // ints sent as numbers (swing, convertible, gen mode)
        case status                    // read-only
        case cycle([String])           // single value stepped in order on each tap (nanoeGA off → on → auto)
        case core                      // power/mode/temperature/fan: handled by the main card
        case other                     // known to the model but not rendered here
    }

    struct IntOption: Equatable, Hashable {
        let value: Int
        let label: String
    }

    let name: String
    let key: String
    let ki: Int
    let kind: Kind
    /// Raw `values` / `intValues` from the model attribute, for diagnostics.
    var declaredValues: [String] = []

    var id: String { name }
    var label: String { ACCapability.catalog[name]?.label ?? name }
    var symbol: String { ACCapability.catalog[name]?.symbol ?? "slider.horizontal.3" }

    /// Short description of allowed values for diagnostics.
    var valuesSummary: String {
        switch kind {
        case .toggle: "on | off"
        case .choice(let v): v.joined(separator: " | ")
        case .intChoice(let o): o.map { "\($0.value)=\($0.label)" }.joined(separator: " ")
        case .cycle(let v): v.joined(separator: " → ")
        case .status, .core, .other: declaredValues.isEmpty ? "—" : declaredValues.joined(separator: " | ")
        }
    }

    var kindName: String {
        switch kind {
        case .toggle: "toggle"
        case .choice: "choice"
        case .intChoice: "int-choice"
        case .status: "status"
        case .cycle: "cycle"
        case .core: "core"
        case .other: "other"
        }
    }

    // MARK: Catalogue (BaseCapability.Type)

    struct Info {
        let ki: Int
        let label: String
        let symbol: String
    }

    static let toggleNames: [String] = [
        "PowerfulMode", "ECOMode", "EvaporatorClean", "NanoeG", "NanoeX", "JetStream", "TurboMode",
        "Ionizer", "MotionSensor", "EcoNavi", "EconomySensor", "Humidifier", "LaundryMode", "BuzzerControl",
    ]

    static let statusNames: [String] = [
        "RoomHumidity", "FilterDustLevel", "TimeBasedFilterHealth", "PM25Sensor", "CurrentPowerConsumption", "ACFaults",
    ]

    static let catalog: [String: Info] = [
        "ACMode": Info(ki: 0, label: "Mode", symbol: "dial.medium"),
        "PowerStatus": Info(ki: 1, label: "Power", symbol: "power"),
        "ACTemperature": Info(ki: 2, label: "Temperature", symbol: "thermometer.medium"),
        "PowerfulMode": Info(ki: 3, label: "Powerful", symbol: "bolt.fill"),
        "ACFanSpeed": Info(ki: 4, label: "Fan", symbol: "fan"),
        "VerticalSwing": Info(ki: 5, label: "Vertical swing", symbol: "arrow.up.and.down"),
        "HorizontalSwing": Info(ki: 6, label: "Horizontal swing", symbol: "arrow.left.and.right"),
        "NanoeG": Info(ki: 7, label: "nanoe G", symbol: "aqi.medium"),
        "DisplayControl": Info(ki: 8, label: "Display", symbol: "display"),
        "EvaporatorClean": Info(ki: 9, label: "Clean", symbol: "sparkles"),
        "ECOMode": Info(ki: 10, label: "ECO", symbol: "leaf.fill"),
        "SleepProfile": Info(ki: 12, label: "Sleep profile", symbol: "moon.zzz"),
        "JetStream": Info(ki: 13, label: "Jet stream", symbol: "wind"),
        "ACTimer": Info(ki: 14, label: "Timer", symbol: "timer"),
        "FilterDustLevel": Info(ki: 15, label: "Filter", symbol: "line.3.horizontal.decrease"),
        "Ionizer": Info(ki: 16, label: "Ionizer", symbol: "atom"),
        "MotionSensor": Info(ki: 17, label: "Motion sensor", symbol: "figure.walk"),
        "TurboMode": Info(ki: 18, label: "Turbo", symbol: "hare.fill"),
        "ACGenMode": Info(ki: 19, label: "Capacity", symbol: "gauge.with.dots.needle.67percent"),
        "PM25Sensor": Info(ki: 20, label: "PM2.5", symbol: "aqi.low"),
        "EcoNavi": Info(ki: 21, label: "ECONAVI", symbol: "eye"),
        "NanoeX": Info(ki: 23, label: "nanoe X", symbol: "aqi.high"),
        "NanoeGA": Info(ki: 24, label: "nanoe GA", symbol: "aqi.medium"),
        "FilterCalibration": Info(ki: 26, label: "Filter reset", symbol: "arrow.counterclockwise"),
        "LaundryMode": Info(ki: 29, label: "Laundry", symbol: "tshirt"),
        "Humidifier": Info(ki: 258, label: "Humidifier", symbol: "humidifier"),
        "TimeBasedFilterHealth": Info(ki: 25, label: "Filter health", symbol: "line.3.horizontal.decrease"),
        "EconomySensor": Info(ki: 27, label: "Economy sensor", symbol: "sensor"),
        "Convertible": Info(ki: 28, label: "Convertible", symbol: "gauge.with.dots.needle.67percent"),
        "BuzzerControl": Info(ki: 30, label: "Beep", symbol: "speaker.wave.2"),
        "RoomTemperature": Info(ki: 101, label: "Room temperature", symbol: "thermometer.medium"),
        "RoomHumidity": Info(ki: 102, label: "Humidity", symbol: "humidity"),
        "CurrentPowerConsumption": Info(ki: 104, label: "Power", symbol: "bolt"),
        "ACFaults": Info(ki: 107, label: "Faults", symbol: "exclamationmark.triangle"),
    ]

    /// ACGenMode sends a level code, not the percentage (ACGenModeCapability.Type).
    static let genModeCodes: [String: Int] = ["40": 5, "60": 4, "80": 3, "100": 2, "110": 1, "OFF": 0]
    static let convertibleDefaults = ["40", "50", "55", "60", "70", "80", "90", "100", "110", "OFF"]

    // MARK: Parsing

    /// Parses a stored model blob `{controlCapabilities, configCapabilities, statusCapabilitiesV2}`.
    static func parse(model: JSONValue?) -> [ACCapability] {
        guard let model else { return [] }
        var out: [ACCapability] = []
        var seen = Set<String>()
        for (field, isStatus) in [("controlCapabilities", false), ("statusCapabilitiesV2", true)] {
            for cap in model[field]?.arrayValue ?? [] {
                guard let name = cap["capability"]?.stringValue, !seen.contains(name) else { continue }
                seen.insert(name)
                out.append(parse(cap: cap, name: name, isStatus: isStatus))
            }
        }
        return out
    }

    private static func parse(cap: JSONValue, name: String, isStatus: Bool) -> ACCapability {
        let attr = cap["attributes"]?.arrayValue.first
        let key = attr?["key"]?.stringValue ?? name
        let ki = catalog[name]?.ki ?? -1
        let strings = attr?["values"]?.arrayValue.compactMap(\.stringValue) ?? []
        let ints = attr?["intValues"]?.arrayValue.compactMap(\.intValue) ?? []

        let kind: Kind
        switch name {
        case _ where toggleNames.contains(name):
            kind = .toggle
        case "DisplayControl":
            kind = .choice(strings.isEmpty ? ["on", "night", "off"] : strings)
        case "NanoeGA":
            kind = .cycle(["off", "on", "auto"])
        case "VerticalSwing", "HorizontalSwing":
            kind = .intChoice(swingOptions(name: name, ints: ints, range: attr?["range"]))
        case "Convertible":
            // Prefer the model's `intValues` (e.g. [110,100,90,80,70,55,40,0]); fall back to strings, then defaults.
            var values = ints
            if values.isEmpty {
                values = (strings.isEmpty ? convertibleDefaults : strings).map { $0.uppercased() == "OFF" ? 0 : Int($0) ?? 0 }
            }
            let ordered = values.filter { $0 > 0 }.sorted() + (values.contains(0) ? [0] : [])
            kind = .intChoice(ordered.map { v in
                v == 0 ? IntOption(value: 0, label: "Off") : IntOption(value: v, label: "\(v)%")
            })
        case "ACGenMode":
            let values = strings.isEmpty ? ["40", "60", "80", "100", "110", "OFF"] : strings
            kind = .intChoice(values.compactMap { v in
                guard let code = genModeCodes[v.uppercased()] else { return nil }
                return IntOption(value: code, label: v.uppercased() == "OFF" ? "Off" : "\(v)%")
            })
        case "PowerStatus", "ACMode", "ACTemperature", "ACFanSpeed":
            kind = .core
        case _ where statusNames.contains(name) || isStatus:
            kind = .status
        default:
            kind = .other
        }
        return ACCapability(name: name, key: key, ki: ki, kind: kind,
                            declaredValues: strings.isEmpty ? ints.map(String.init) : strings)
    }

    /// Swing positions: `intValues` when given, else min…max by increment. The official UI shows
    /// positions 1–5 and an "auto" icon for anything else (0); horizontal 32 shows "off".
    private static func swingOptions(name: String, ints: [Int], range: JSONValue?) -> [IntOption] {
        var values = ints
        if values.isEmpty {
            let lo = range?["min"]?.arrayValue.first?["value"]?.intValue ?? 0
            let hi = range?["max"]?.arrayValue.first?["value"]?.intValue ?? 5
            let step = max(1, range?["increment"]?.arrayValue.first?["value"]?.intValue ?? 1)
            values = lo <= hi ? Array(stride(from: lo, through: hi, by: step)) : []
        }
        return values.map { v in
            let label = switch v {
            case 1...5: "\(v)"
            case 32 where name == "HorizontalSwing": "Off"
            default: "Auto"
            }
            return IntOption(value: v, label: label)
        }
    }
}

// MARK: Model-derived core ranges

/// Numeric range from a model attribute (`range.min/max/increment`, each a list of `{unit, value}`).
struct ACRange: Equatable {
    var min: Double
    var max: Double
    var step: Double
}

extension ACCapability {
    /// First attribute of the named capability in a stored model blob.
    static func attribute(_ name: String, in model: JSONValue?) -> JSONValue? {
        for field in ["controlCapabilities", "statusCapabilitiesV2"] {
            for cap in model?[field]?.arrayValue ?? [] where cap["capability"]?.stringValue == name {
                return cap["attributes"]?.arrayValue.first
            }
        }
        return nil
    }

    /// Reads `min`/`max`/`increment`, preferring the Celsius entry (the model spells it "celcius").
    static func range(_ name: String, in model: JSONValue?) -> ACRange? {
        guard let r = attribute(name, in: model)?["range"] else { return nil }
        func pick(_ field: String) -> Double? {
            let entries = r[field]?.arrayValue ?? []
            let celsius = entries.first { ["celcius", "celsius"].contains($0["unit"]?.stringValue?.lowercased() ?? "") }
            return (celsius ?? entries.first)?["value"]?.doubleValue
        }
        guard let lo = pick("min"), let hi = pick("max"), lo <= hi else { return nil }
        return ACRange(min: lo, max: hi, step: Swift.max(pick("increment") ?? 1, 0.5))
    }

    /// String `values` of the named capability's first attribute.
    static func values(_ name: String, in model: JSONValue?) -> [String] {
        attribute(name, in: model)?["values"]?.arrayValue.compactMap(\.stringValue) ?? []
    }
}

extension ACDevice {
    /// Modes the model allows (e.g. no Heat on cooling-only units); app defaults without a model.
    var modeOptions: [String] {
        let v = ACCapability.values("ACMode", in: model)
        return v.isEmpty ? ACStore.modes : v
    }

    /// Fan speeds the model allows; app defaults without a model.
    var fanOptions: [String] {
        let v = ACCapability.values("ACFanSpeed", in: model)
        return v.isEmpty ? ACStore.fanSpeeds : v
    }

    /// Target temperature range and step (°C); 16–30 in 0.5° steps without a model.
    var tempRange: ACRange {
        ACCapability.range("ACTemperature", in: model) ?? ACRange(min: 16, max: 30, step: 0.5)
    }

    /// Timer range in minutes, when the model lists ACTimer.
    var timerRange: ACRange? {
        guard capabilities.contains(where: { $0.name == "ACTimer" }) else { return nil }
        return ACCapability.range("ACTimer", in: model) ?? ACRange(min: 10, max: 1440, step: 10)
    }

    func capability(_ name: String) -> ACCapability? { capabilities.first { $0.name == name } }
}
