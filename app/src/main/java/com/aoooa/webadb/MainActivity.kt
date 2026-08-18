package com.aoooa.webadb

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.aoooa.webadb.ui.WebAdbApp

/**
 * WebADB 控制台 2.0（原生版）入口。
 * 纯 Compose UI，无 WebView。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WebAdbApp()
        }
    }
}
