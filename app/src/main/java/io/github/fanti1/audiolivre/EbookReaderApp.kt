package io.github.fanti1.audiolivre

import android.app.Application
import io.github.fanti1.audiolivre.data.CrashLog
import io.github.fanti1.audiolivre.data.EventLog
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class EbookReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        EventLog.install(this)
        PDFBoxResourceLoader.init(applicationContext)
    }
}
