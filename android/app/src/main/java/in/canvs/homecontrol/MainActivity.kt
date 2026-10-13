package `in`.canvs.homecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import `in`.canvs.homecontrol.ui.HomeControlRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent.getBooleanExtra("demo", false)) {
            app.ac.seedDemo()
            app.lights.seedDemo()
        }
        setContent { HomeControlRoot(app.ac, app.lights) }
    }
}
