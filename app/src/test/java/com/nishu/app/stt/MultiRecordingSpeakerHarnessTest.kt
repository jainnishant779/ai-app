package com.nishu.app.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Automated multi-recording test harness for speaker voice memory and biometric matching.
 * Verifies enrollment, session-to-session persistence, impostor rejection, FAR/FRR metrics,
 * and multi-speaker disambiguation.
 */
class MultiRecordingSpeakerHarnessTest {

    // Helper to generate normalized random 512-dimensional embeddings
    private fun generateUnitEmbedding(seed: Int): FloatArray {
        val rng = Random(seed)
        val raw = FloatArray(512) { rng.nextFloat() * 2f - 1f }
        return SpeakerVoiceMatcher.l2Normalize(raw)
    }

    /**
     * Simulates acoustic variation of the same speaker across different rooms and mic distances.
     * In 512 dimensions, blending the base unit vector with an orthogonal direction preserves
     * hypersphere geometry where cosine similarity closely targets [similarityTarget].
     */
    private fun perturbEmbedding(base: FloatArray, similarityTarget: Float, seed: Int): FloatArray {
        val noise = generateUnitEmbedding(seed)
        val alpha = similarityTarget.coerceIn(0.1f, 0.99f)
        val beta = sqrt(1f - alpha * alpha)
        val blended = FloatArray(base.size) { i ->
            alpha * base[i] + beta * noise[i]
        }
        return SpeakerVoiceMatcher.l2Normalize(blended)
    }

    @Test
    fun testMultiRecordingEnrollmentAndCrossSessionMatch() {
        val nishantBase = generateUnitEmbedding(42)

        // Session 1: Initial enrollment (clean recording, similarity ~ 0.85)
        val session1Sample = perturbEmbedding(nishantBase, similarityTarget = 0.85f, seed = 101)
        var enrolledProfile = session1Sample.clone()
        var sampleCount = 1

        // Session 2: Independent recording with distance and room variation (sim ~ 0.66, matching our device logcat)
        val session2Sample = perturbEmbedding(nishantBase, similarityTarget = 0.66f, seed = 102)
        val similarityToEnrolled = SpeakerVoiceMatcher.cosineSimilarity(session2Sample, enrolledProfile)

        // Intra-speaker similarity must exceed the calibrated threshold (0.50)
        assertTrue(
            "Session 2 of same speaker must match enrolled profile (similarity: $similarityToEnrolled)",
            similarityToEnrolled >= SpeakerVoiceMatcher.MATCH_THRESHOLD
        )

        // Running average update with L2 normalization
        val merged = FloatArray(enrolledProfile.size) { i ->
            (enrolledProfile[i] * sampleCount + session2Sample[i]) / (sampleCount + 1)
        }
        enrolledProfile = SpeakerVoiceMatcher.l2Normalize(merged)
        sampleCount++

        // Session 3: Further independent recording (sim ~ 0.62)
        val session3Sample = perturbEmbedding(nishantBase, similarityTarget = 0.62f, seed = 103)
        val similarityToMerged = SpeakerVoiceMatcher.cosineSimilarity(session3Sample, enrolledProfile)
        assertTrue(
            "Session 3 of same speaker must match updated multi-session profile (similarity: $similarityToMerged)",
            similarityToMerged >= SpeakerVoiceMatcher.MATCH_THRESHOLD
        )
    }

    @Test
    fun testUnknownSpeakerRejection() {
        val nishantVoice = generateUnitEmbedding(42)
        val impostorVoice = generateUnitEmbedding(999) // Independent random acoustic identity

        val similarity = SpeakerVoiceMatcher.cosineSimilarity(impostorVoice, nishantVoice)
        assertTrue(
            "Impostor voice must be rejected below match threshold (similarity was $similarity)",
            similarity < SpeakerVoiceMatcher.MATCH_THRESHOLD
        )
    }

    @Test
    fun testMultipleEnrolledSpeakersDisambiguation() {
        val speaker1 = generateUnitEmbedding(10) // "Nishant"
        val speaker2 = generateUnitEmbedding(20) // "Aarav"
        val speaker3 = generateUnitEmbedding(30) // "Priya"

        val profiles = mapOf(
            "Nishant" to speaker1,
            "Aarav" to speaker2,
            "Priya" to speaker3,
        )

        // Test probe for Aarav with ambient phone variation (target similarity ~ 0.70)
        val probeAarav = perturbEmbedding(speaker2, similarityTarget = 0.70f, seed = 777)

        val scores = profiles.mapValues { (_, emb) ->
            SpeakerVoiceMatcher.cosineSimilarity(probeAarav, emb)
        }

        val bestMatch = scores.maxByOrNull { it.value }
        assertNotNull(bestMatch)
        assertEquals("Best match must be Aarav", "Aarav", bestMatch!!.key)
        assertTrue("Aarav similarity must exceed threshold", bestMatch.value >= SpeakerVoiceMatcher.MATCH_THRESHOLD)
        assertTrue("Other speaker similarities must be significantly lower", scores["Nishant"]!! < 0.35f)
        assertTrue("Other speaker similarities must be significantly lower", scores["Priya"]!! < 0.35f)
    }

    @Test
    fun testFARAndFRRBenchmarkAcrossSyntheticPopulation() {
        val numGenuineUsers = 5
        val sessionsPerUser = 4
        val numImpostors = 10

        val database = mutableMapOf<Int, FloatArray>()
        // Enroll genuine users
        for (u in 0 until numGenuineUsers) {
            val base = generateUnitEmbedding(u * 100)
            database[u] = base
        }

        var falseRejections = 0
        var totalGenuineTrials = 0

        // Test genuine trials (genuine speaker similarity typically 0.60 - 0.75 across real recordings)
        for (u in 0 until numGenuineUsers) {
            val base = database[u]!!
            for (s in 1..sessionsPerUser) {
                totalGenuineTrials++
                val testProbe = perturbEmbedding(base, similarityTarget = 0.64f, seed = u * 1000 + s)
                val score = SpeakerVoiceMatcher.cosineSimilarity(testProbe, base)
                if (score < SpeakerVoiceMatcher.MATCH_THRESHOLD) {
                    falseRejections++
                }
            }
        }

        var falseAcceptances = 0
        var totalImpostorTrials = 0

        // Test impostor trials
        for (imp in 0 until numImpostors) {
            val impVoice = generateUnitEmbedding(5000 + imp)
            for (u in 0 until numGenuineUsers) {
                totalImpostorTrials++
                val score = SpeakerVoiceMatcher.cosineSimilarity(impVoice, database[u]!!)
                if (score >= SpeakerVoiceMatcher.MATCH_THRESHOLD) {
                    falseAcceptances++
                }
            }
        }

        val frr = falseRejections.toFloat() / totalGenuineTrials
        val far = falseAcceptances.toFloat() / totalImpostorTrials

        // Assert reliability constraints:
        // Genuine matches should have 0 false rejections at target similarity 0.64
        assertEquals("FRR should be 0% at similarity target 0.64 (was $frr)", 0f, frr, 1e-4f)
        assertEquals("FAR must be 0% for independent speakers (was $far)", 0f, far, 1e-4f)
    }
}
