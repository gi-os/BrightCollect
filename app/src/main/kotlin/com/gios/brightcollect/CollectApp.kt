package com.gios.brightcollect

import android.app.Application
import com.gios.light.common.report.LightReport

/**
 * Registers the app with light-common's reporter, and nothing else.
 *
 * [LightReport.install] installs the crash handler on the way through, so this has to run
 * before the first activity exists — `Application.onCreate` is the only hook early enough.
 *
 * The ONNX session is deliberately **not** warmed up here. It costs tens of megabytes of native
 * allocation and about a second, and doing it on the way to the launcher would delay every cold
 * start including the many that only open the shelf and never take a photograph.
 */
class CollectApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LightReport.install(
            context = this,
            appName = "Collect",
            label = "collect",
            // Empty in a build with no secret. Reports queue on the phone and go out from a
            // later build that has the key, so this is not a failure worth handling.
            token = BuildConfig.REPORT_TOKEN,
            repo = BuildConfig.REPORT_REPO,
        )
    }
}
