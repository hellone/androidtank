package com.manaa.tankwar

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ⚠️ x,y sont NORMALISÉS (0..1) côté réseau
data class NetPlayerState(
    val name: String = "",
    val x: Float = 0f,   // 0..1
    val y: Float = 0f,   // 0..1
    val angle: Float = 0f,
    val ts: Long = 0L
)

data class RoomSlots(var p1: String? = null, var p2: String? = null)

class Online(
    private val roomId: String,
    databaseUrl: String // ex: https://tank-xxxx-default-rtdb.europe-west1.firebasedatabase.app
) {
    private val auth = Firebase.auth
    private val db = Firebase.database(databaseUrl)

    private val roomRef: DatabaseReference = db.getReference("rooms").child(roomId)
    private val slotsRef: DatabaseReference = roomRef.child("slots")
    private lateinit var meRef: DatabaseReference

    var myId: String = ""
        private set
    var mySlot: String? = null
        private set

    suspend fun connect(name: String = "Player"): String {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        myId = auth.currentUser!!.uid

        cleanupStalePlayers(staleMs = 10_000)
        mySlot = claimSlotOrThrow()

        meRef = roomRef.child("players").child(myId)
        meRef.onDisconnect().removeValue()
        mySlot?.let { slotsRef.child(it).onDisconnect().setValue(null) }

        meRef.setValue(NetPlayerState(name = name, ts = System.currentTimeMillis()))
        return myId
    }

    fun playersRef(): DatabaseReference = roomRef.child("players")
    fun slotsLiveRef(): DatabaseReference = slotsRef

    // Envoi d'état : xNorm,yNorm ∈ [0..1]
    fun updateMyState(xNorm: Float, yNorm: Float, angle: Float) {
        val xn = xNorm.coerceIn(0f, 1f)
        val yn = yNorm.coerceIn(0f, 1f)
        meRef.updateChildren(
            mapOf(
                "x" to xn,
                "y" to yn,
                "angle" to angle,
                "ts" to ServerValue.TIMESTAMP
            )
        ).addOnFailureListener { e -> Log.e("FB", "update FAIL", e) }
    }

    fun disconnect() {
        try {
            if (::meRef.isInitialized) meRef.removeValue()
            mySlot?.let { slotsRef.child(it).removeValue() }
        } catch (_: Exception) {}
    }

    // -------------------- Helpers --------------------

    private suspend fun claimSlotOrThrow(): String =
        suspendCancellableCoroutine { cont ->
            slotsRef.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    val p1 = currentData.child("p1").getValue(String::class.java)
                    val p2 = currentData.child("p2").getValue(String::class.java)

                    if (p1 == myId || p2 == myId) return Transaction.success(currentData)

                    return when {
                        p1.isNullOrEmpty() -> {
                            currentData.child("p1").value = myId
                            Transaction.success(currentData)
                        }
                        p2.isNullOrEmpty() -> {
                            currentData.child("p2").value = myId
                            Transaction.success(currentData)
                        }
                        else -> Transaction.abort()
                    }
                }

                override fun onComplete(
                    error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?
                ) {
                    if (error != null) {
                        if (!cont.isCompleted) cont.resumeWithException(error.toException())
                        return
                    }
                    if (!committed) {
                        if (!cont.isCompleted) cont.resumeWithException(IllegalStateException("ROOM_FULL"))
                        return
                    }
                    val p1 = snapshot?.child("p1")?.getValue(String::class.java)
                    val p2 = snapshot?.child("p2")?.getValue(String::class.java)
                    val slot = when (myId) {
                        p1 -> "p1"
                        p2 -> "p2"
                        else -> null
                    }
                    if (slot == null) {
                        if (!cont.isCompleted) cont.resumeWithException(IllegalStateException("SLOT_NOT_ASSIGNED"))
                    } else {
                        if (!cont.isCompleted) cont.resume(slot)
                    }
                }
            })
        }

    private suspend fun cleanupStalePlayers(staleMs: Long) {
        val now = System.currentTimeMillis()
        val playersSnap = roomRef.child("players").get().await()
        for (child in playersSnap.children) {
            val st = child.getValue(NetPlayerState::class.java) ?: continue
            val uid = child.key ?: continue
            if (now - st.ts > staleMs) {
                roomRef.child("players").child(uid).removeValue()
                val slots = slotsRef.get().await()
                val p1 = slots.child("p1").getValue(String::class.java)
                val p2 = slots.child("p2").getValue(String::class.java)
                when (uid) {
                    p1 -> slotsRef.child("p1").setValue(null)
                    p2 -> slotsRef.child("p2").setValue(null)
                }
            }
        }
    }
}
