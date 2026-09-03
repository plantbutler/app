package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun dose(
    id: Long = 1,
    ml: Int? = 100,
    flowMl: Int? = null,
    state: String = "acked",
    source: String? = "manual",
    sentTs: Long? = 1000,
    ackedTs: Long? = 1010,
    createdTs: Long? = 990,
    verdict: String? = null,
    pot: String? = "pot-1",
    potName: String? = "basil",
    kind: String = "water",
) = Dose(id, kind, ml, 30, flowMl, state, source, createdTs, sentTs, ackedTs, verdict, pot, potName)

class DosesTest {
    @Test
    fun `a dose that flowed short is the one the backend would alert on`() {
        // The backend's rule, to the operator: 2 * flow_ml < ml.
        assertTrue(flowedShort(dose(ml = 100, flowMl = 12)))
        assertTrue(flowedShort(dose(ml = 100, flowMl = 49)))
        assertFalse(flowedShort(dose(ml = 100, flowMl = 50))) // exactly half is not short
        assertFalse(flowedShort(dose(ml = 100, flowMl = 96)))
        assertFalse(flowedShort(dose(ml = 100, flowMl = 140))) // over is not short
        // Nothing to compare is not a symptom.
        assertFalse(flowedShort(dose(ml = null, flowMl = 12)))
        assertFalse(flowedShort(dose(ml = 100, flowMl = null)))
    }

    @Test
    fun `the rows worth reading say why, and an ordinary dose says nothing`() {
        assertEquals(
            "expired — the board never acknowledged it, so nobody knows whether it poured",
            doseTrouble(dose(state = "expired", ackedTs = null)),
        )
        assertEquals("the board reported it failed", doseTrouble(dose(state = "failed")))
        assertEquals("the meter counted 12 of 100 ml", doseTrouble(dose(flowMl = 12)))
        assertNull(doseTrouble(dose(flowMl = 96)))
        assertNull(doseTrouble(dose(state = "sent", ackedTs = null)))
        // A short count only means something once the board has acked it.
        assertNull(doseTrouble(dose(state = "sent", flowMl = 12, ackedTs = null)))
    }

    @Test
    fun `the line says the dose, when, and how it ended`() {
        assertEquals(
            "100 ml · 2h ago · acked, meter 96 ml",
            doseHistoryLine(dose(flowMl = 96, ackedTs = 1000), 1000 + 2 * 3600),
        )
        assertEquals(
            "100 ml · 2h ago · expired, never acked",
            doseHistoryLine(dose(state = "expired", ackedTs = null, sentTs = 1000), 1000 + 2 * 3600),
        )
        assertEquals(
            "100 ml · 2h ago · handed to the board, not acked yet",
            doseHistoryLine(dose(state = "sent", ackedTs = null, sentTs = 1000), 1000 + 2 * 3600),
        )
        // Never handed out: the only time it has is when it was made.
        assertEquals(
            "100 ml · 2h ago · queued, never handed out",
            doseHistoryLine(
                dose(state = "queued", ackedTs = null, sentTs = null, createdTs = 1000),
                1000 + 2 * 3600,
            ),
        )
        assertEquals("? ml · 2h ago · acked", doseHistoryLine(dose(ml = null, ackedTs = 1000), 1000 + 2 * 3600))
        // The backend filters stops out of /doses, so this is defence for a
        // field the wire still carries rather than a row the app will meet:
        // if one ever arrives it names itself instead of posing as 0 ml.
        assertEquals("stop · 2h ago · acked", doseHistoryLine(dose(kind = "stop", ackedTs = 1000), 1000 + 2 * 3600))
    }

    @Test
    fun `an unattributable dose says so instead of borrowing a name`() {
        assertEquals("basil", doseWho(dose()))
        assertEquals("no pot on that hose then", doseWho(dose(pot = null, potName = null)))
        // Never handed out is not the same as "the windows say nobody was
        // there": there is simply nothing to attribute yet.
        assertEquals(
            "not handed out yet",
            doseWho(dose(pot = null, potName = null, state = "queued", sentTs = null, ackedTs = null)),
        )
    }

    @Test
    fun `the source is spelt for a human`() {
        assertEquals("by hand", doseSource(dose(source = "manual")))
        assertEquals("by the rules", doseSource(dose(source = "rules")))
        assertEquals("something_new", doseSource(dose(source = "something_new")))
        assertNull(doseSource(dose(source = null)))
    }
}
