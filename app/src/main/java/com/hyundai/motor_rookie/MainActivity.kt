package com.hyundai.motor_rookie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.hyundai.motor_rookie.ui.PowerNapApp

class MainActivity : ComponentActivity() {

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 거부 시 퀴즈는 터치 보기로만 답할 수 있습니다 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 파워냅 중 화면이 꺼져 타이머 표시가 멈추지 않도록 합니다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        // 세팅 화면 왼쪽 위 '뒤로 가기'는 앱을 닫습니다.
        setContent { PowerNapApp(onExit = ::finish) }
    }
}
