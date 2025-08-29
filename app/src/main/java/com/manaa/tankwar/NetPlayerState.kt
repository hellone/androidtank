package com.manaa.tankwar

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.database
import kotlinx.coroutines.tasks.await

data class NetPlayerState(
    val name: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val angle: Float = 0f,
    val ts: Long = 0L
)

class Online(
    private val roomId: String,
    databaseUrl: String // on force l’URL de ta RTDB
) {
    private val auth = Firebase.auth
    private val db = Firebase.database(databaseUrl)

    private lateinit var roomRef: DatabaseReference
    private lateinit var meRef: DatabaseReference

    var myId: String = ""

    suspend fun connect(name: String = "Player"): String {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        myId = auth.currentUser!!.uid

        roomRef = db.getReference("rooms").child(roomId)
        meRef = roomRef.child("players").child(myId)

        meRef.onDisconnect().removeValue()
        meRef.setValue(NetPlayerState(name = name, ts = System.currentTimeMillis()))
        return myId
    }

    fun playersRef(): DatabaseReference = roomRef.child("players")

    fun updateMyState(x: Float, y: Float, angle: Float) {
        meRef.updateChildren(
            mapOf(
                "x" to x,
                "y" to y,
                "angle" to angle,
                "ts" to System.currentTimeMillis()
            )
        )
    }

    fun disconnect() {
        if (::meRef.isInitialized) meRef.removeValue()
    }
}
