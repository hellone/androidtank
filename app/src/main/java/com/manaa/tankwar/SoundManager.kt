package com.manaa.tankwar

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.util.concurrent.ConcurrentHashMap

class SoundManager(context: Context) {

    private val soundPool: SoundPool
    private val idShot: Int
    private val idExplosion: Int
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setAudioAttributes(attrs)
            .setMaxStreams(6)
            .build()

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded += sampleId
        }

        // Charger tes fichiers mp3 depuis res/raw
        idShot = soundPool.load(context, R.raw.shot, 1)
        idExplosion = soundPool.load(context, R.raw.explosion, 1)
    }

    fun playShot() {
        if (idShot in loaded) {
            soundPool.play(idShot, 1f, 1f, 1, 0, 1.0f)
        }
    }

    fun playExplosion() {
        if (idExplosion in loaded) {
            soundPool.play(idExplosion, 1f, 1f, 1, 0, 1.0f)
        }
    }

    fun release() {
        soundPool.release()
    }
}
