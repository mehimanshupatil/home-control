package `in`.canvs.homecontrol

import android.app.Application
import android.content.Context
import `in`.canvs.homecontrol.data.SecureStore
import `in`.canvs.homecontrol.miraie.AcRepository
import `in`.canvs.homecontrol.wiz.LightsRepository
import `in`.canvs.homecontrol.wiz.WizClient

class HomeControlApp : Application() {
    val store by lazy { SecureStore(this) }
    val ac by lazy { AcRepository(this, store) }
    val lights by lazy { LightsRepository(WizClient(this), store) }
}

val Context.app: HomeControlApp get() = applicationContext as HomeControlApp
