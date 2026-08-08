package dev.helmcode.helm.attribution

import dev.helmcode.helm.analytics.InMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline queue's durability contract: what round-trips, what dedupes, what
 * expires, and what survives a corrupt blob. A fixed clock makes retention
 * assertions exact.
 */
class PendingSubmissionStoreTest {

    private val now = 1_765_200_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val backing = InMemoryStore()
    private val store = PendingSubmissionStore(backing) { now }

    @Test
    fun bothKindsRoundTripThroughJson() {
        store.enqueue(PendingSubmission.promoCode("user-1", "anna", now))
        store.enqueue(PendingSubmission.transaction("user-1", "tx-9", now))

        val entries = store.all()
        assertEquals(2, entries.size)

        val promo = entries.first { it.kind == PendingKind.PROMO_CODE }
        assertEquals("user-1", promo.userId)
        assertEquals("anna", promo.code)
        assertNull(promo.originalTransactionId)
        assertEquals(now, promo.enqueuedAtMs)
        assertTrue(promo.id.isNotEmpty())

        val transaction = entries.first { it.kind == PendingKind.TRANSACTION }
        assertEquals("tx-9", transaction.originalTransactionId)
        assertNull(transaction.code)
    }

    @Test
    fun enqueueDedupesSameLogicalSubmissionAndKeepsOriginalTimestamp() {
        store.enqueue(PendingSubmission.promoCode("user-1", "anna", now - 5 * day))
        store.enqueue(PendingSubmission.promoCode("user-1", "anna", now))

        val entries = store.all()
        assertEquals(1, entries.size)
        // The retention window is measured from the FIRST failure, so a
        // repeatedly-retried submission cannot keep itself alive forever.
        assertEquals(now - 5 * day, entries.single().enqueuedAtMs)
    }

    @Test
    fun differentUsersAndCodesAreDistinctEntries() {
        store.enqueue(PendingSubmission.promoCode("user-1", "anna", now))
        store.enqueue(PendingSubmission.promoCode("user-2", "anna", now))
        store.enqueue(PendingSubmission.promoCode("user-1", "bruno", now))
        store.enqueue(PendingSubmission.transaction("user-1", "anna", now))

        assertEquals(4, store.all().size)
    }

    @Test
    fun entriesPastRetentionAreDroppedOnRead() {
        store.enqueue(PendingSubmission.promoCode("stale", "old-code", now - 31 * day))
        store.enqueue(PendingSubmission.promoCode("fresh", "new-code", now - 29 * day))

        val entries = store.all()
        assertEquals(1, entries.size)
        assertEquals("fresh", entries.single().userId)
        // The prune is persisted, not just filtered on the way out.
        assertEquals(1, store.count())
    }

    @Test
    fun allReturnsOldestFirst() {
        store.enqueue(PendingSubmission.promoCode("third", "c", now - 1 * day))
        store.enqueue(PendingSubmission.promoCode("first", "a", now - 3 * day))
        store.enqueue(PendingSubmission.promoCode("second", "b", now - 2 * day))

        assertEquals(listOf("first", "second", "third"), store.all().map { it.userId })
    }

    @Test
    fun capEvictsOldestEntries() {
        repeat(PendingSubmissionStore.MAX_PENDING) { index ->
            store.enqueue(PendingSubmission.promoCode("user-$index", "code-$index", now - (200 - index) * 1000L))
        }
        assertEquals(PendingSubmissionStore.MAX_PENDING, store.count())

        store.enqueue(PendingSubmission.promoCode("newcomer", "code-new", now))

        val entries = store.all()
        assertEquals(PendingSubmissionStore.MAX_PENDING, entries.size)
        assertTrue(entries.any { it.userId == "newcomer" })
        assertTrue("oldest entry must be shed", entries.none { it.userId == "user-0" })
    }

    @Test
    fun corruptJsonReadsAsEmptyAndSelfHealsOnNextWrite() {
        backing.put(PendingSubmissionStore.KEY, "{not-an-array")

        assertEquals(0, store.all().size)

        store.enqueue(PendingSubmission.promoCode("user-1", "anna", now))
        assertEquals(1, store.all().size)
    }

    @Test
    fun rowsMissingRequiredFieldsAreSkipped() {
        backing.put(
            PendingSubmissionStore.KEY,
            """[{"id":"a","kind":"promo_code"},{"id":"b","kind":"nonsense","user_id":"u","enqueued_at_ms":$now}]""",
        )
        assertEquals(0, store.all().size)
    }

    @Test
    fun removeDropsOnlyTheNamedEntry() {
        store.enqueue(PendingSubmission.promoCode("user-1", "anna", now))
        store.enqueue(PendingSubmission.promoCode("user-2", "bruno", now))
        val target = store.all().first { it.userId == "user-1" }

        store.remove(target.id)

        assertEquals(listOf("user-2"), store.all().map { it.userId })
    }

    @Test
    fun clearEmptiesTheQueue() {
        store.enqueue(PendingSubmission.promoCode("user-1", "anna", now))
        store.clear()
        assertEquals(0, store.count())
        assertNull(backing.get(PendingSubmissionStore.KEY))
    }
}
