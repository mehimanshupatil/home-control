import SwiftUI

/// The Command Line Tools SDK declares `@State` as a macro whose compiler plugin ships only with Xcode.
/// Referring to the property-wrapper struct through a typealias bypasses the macro.
typealias ViewState<Value> = SwiftUI.State<Value>
