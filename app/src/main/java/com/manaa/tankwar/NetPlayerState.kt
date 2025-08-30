package com.manaa.tankwar

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Coordonnées normalisées 0..1
data class NetPlayerState(
    val name: String = "",
    val x: Float = 0f,   // 0..1
    val y: Float = 0f,   // 0..1
    val angle: Float = 0f,
    val hp: Int = 3,
    val ts: Long = 0L
)

data class RoomSlots(var p1: String? = null, var p2: String? = null)

// Balle (pos/vitesse normalisées)
data class BulletState(
    val id: String = "",
    val owner: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val ts: Long = 0L
)

class Online(
    private val roomId: String,
    databaseUrl: String
) {
    private val auth = Firebase.auth
    private val db = Firebase.database(databaseUrl)

    private val roomRef: DatabaseReference = db.getReference("rooms").child(roomId)
    private val slotsRef: DatabaseReference = roomRef.child("slots")
    private val playersRef: DatabaseReference = roomRef.child("players")
    private val bulletsRef: DatabaseReference = roomRef.child("bullets")

    private lateinit var meRef: DatabaseReference

    var myId: String = ""
        private set
    var mySlot: String? = null
        private set

    suspend fun connect(name: String = "Player"): String {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        myId = auth.currentUser!!.uid

        // ménage “stale” désactivé si règles strictes
        // cleanupStale(10_000)

        mySlot = claimSlotOrThrow()

        meRef = playersRef.child(myId)
        meRef.onDisconnect().removeValue()
        mySlot?.let { slotsRef.child(it).onDisconnect().setValue(null) }

        meRef.setValue(NetPlayerState(name = name, ts = System.currentTimeMillis(), hp = 3))
        return myId
    }

    fun playersLiveRef(): DatabaseReference = playersRef
    fun slotsLiveRef(): DatabaseReference = slotsRef
    fun bulletsLiveRef(): DatabaseReference = bulletsRef

    fun updateMyState(xNorm: Float, yNorm: Float, angle: Float) {
        meRef.updateChildren(
            mapOf(
                "x" to xNorm.coerceIn(0f, 1f),
                "y" to yNorm.coerceIn(0f, 1f),
                "angle" to angle,
                "ts" to ServerValue.TIMESTAMP
            )
        ).addOnFailureListener { e -> Log.e("FB", "update FAIL", e) }
    }

    fun setMyHp(hp: Int) { meRef.child("hp").setValue(hp) }

    fun spawnBullet(b: BulletState) {
        bulletsRef.child(b.id).setValue(b)
            .addOnFailureListener { e -> Log.e("FB", "spawnBullet FAIL", e) }
    }

    fun updateBullet(b: BulletState) {
        bulletsRef.child(b.id).updateChildren(
            mapOf(
                "x" to b.x.coerceIn(0f, 1f),
                "y" to b.y.coerceIn(0f, 1f),
                "ts" to ServerValue.TIMESTAMP
            )
        )
    }

    fun removeBullet(id: String) { bulletsRef.child(id).removeValue() }
    fun clearBullets() { bulletsRef.removeValue() }

    fun disconnect() {
        try {
            meRef.removeValue()
            mySlot?.let { slotsRef.child(it).removeValue() }
        } catch (_: Exception) {}
    }

    // ---------- helpers ----------
    private suspend fun claimSlotOrThrow(): String =
        suspendCancellableCoroutine { cont ->
            slotsRef.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    val p1 = currentData.child("p1").getValue(String::class.java)
                    val p2 = currentData.child("p2").getValue(String::class.java)
                    if (p1 == myId || p2 == myId) return Transaction.success(currentData)
                    return when {
                        p1.isNullOrEmpty() -> { currentData.child("p1").value = myId; Transaction.success(currentData) }
                        p2.isNullOrEmpty() -> { currentData.child("p2").value = myId; Transaction.success(currentData) }
                        else -> Transaction.abort()
                    }
                }
                override fun onComplete(
                    error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?
                ) {
                    if (error != null) { if (!cont.isCompleted) cont.resumeWithException(error.toException()); return }
                    if (!committed) { if (!cont.isCompleted) cont.resumeWithException(IllegalStateException("ROOM_FULL")); return }
                    val p1 = snapshot?.child("p1")?.getValue(String::class.java)
                    val p2 = snapshot?.child("p2")?.getValue(String::class.java)
                    val slot = when (myId) { p1 -> "p1"; p2 -> "p2"; else -> null }
                    if (slot == null) { if (!cont.isCompleted) cont.resumeWithException(IllegalStateException("SLOT_NOT_ASSIGNED")) }
                    else { if (!cont.isCompleted) cont.resume(slot) }
                }
            })
        }

    @Suppress("unused")
    private suspend fun cleanupStale(staleMs: Long) {
        val now = System.currentTimeMillis()
        val playersSnap = playersRef.get().await()
        for (c in playersSnap.children) {
            val st = c.getValue(NetPlayerState::class.java) ?: continue
            val uid = c.key ?: continue
            if (now - st.ts > staleMs) {
                playersRef.child(uid).removeValue()
                val slotsS = slotsRef.get().await()
                val p1 = slotsS.child("p1").getValue(String::class.java)
                val p2 = slotsS.child("p2").getValue(String::class.java)
                when (uid) { p1 -> slotsRef.child("p1").setValue(null); p2 -> slotsRef.child("p2").setValue(null) }
            }
        }
        val bulletsSnap = bulletsRef.get().await()
        for (c in bulletsSnap.children) {
            val b = c.getValue(BulletState::class.java) ?: continue
            if (now - b.ts > 3000) bulletsRef.child(b.id).removeValue()
        }
    }
}
