package garden.butler.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Arming the recalibration wizard from the form, and putting the board
 * back on the pace it was on. */
class CalibrationWizardTest : ButlerTest() {
    @Test
    fun `the wizard refuses to arm over an unsaved rename`() {
        ready()
        onMain {
            open("pot-1")
            edit("name", "genovese")
            startCalibration()
        }
        val form = waitFor("the note") { (model.screen.value as? Screen.Pot)?.takeIf { it.note != null } }
        assertEquals(
            "save or discard your changes first — the wizard calibrates the stored controller and channel",
            form.note,
        )
        assertEquals(emptyList(), butler.posts())
    }

    @Test
    fun `calibration refuses a pot that is not in manual and posts nothing`() {
        ready()
        onMain {
            open("pot-2")
            startCalibration()
        }
        val form = waitFor("the note") { (model.screen.value as? Screen.Pot)?.takeIf { it.note != null } }
        assertEquals("set the pot to manual first — the rules would water a sensor held in the air", form.note)
        assertEquals(false, form.busy)
        assertEquals(emptyList(), butler.posts())
    }

    @Test
    fun `calibration speeds the board up and forgets a leftover fast pace`() {
        butler.nextS = FAST_NEXT_S
        ready()
        onMain {
            open("pot-1")
            startCalibration()
        }
        val wizard = waitFor("the wizard") { model.screen.value as? Screen.Calibrate }
        assertEquals("c=0 next=5", butler.posts().single().body.readUtf8())
        assertEquals("/interval", butler.posts().single().path)
        assertIs<CalState.SpeedingUp>(wizard.cal)
        assertNull(wizard.cal.prevNextS)
        assertEquals("pot-1", wizard.parent.id)
        assertEquals(false, wizard.parent.busy)
    }

    @Test
    fun `calibration remembers a real override to restore later`() {
        butler.nextS = 120
        ready()
        onMain {
            open("pot-1")
            startCalibration()
        }
        val wizard = waitFor("the wizard") { model.screen.value as? Screen.Calibrate }
        assertEquals("c=0 next=5", butler.posts().single().body.readUtf8())
        assertEquals(120, wizard.cal.prevNextS)
    }

    @Test
    fun `cancelling from air restores the previous pace and pops to the form`() {
        butler.nextS = 120
        ready()
        onMain {
            open("pot-1")
            startCalibration()
        }
        waitFor("the wizard") { model.screen.value as? Screen.Calibrate }
        onMain {
            calEvent(CalEvent.Seen(9000, butler.nowS))
            calEvent(CalEvent.Seen(9010, butler.nowS + 5))
        }
        assertIs<CalState.Air>((model.screen.value as Screen.Calibrate).cal)
        onMain { calEvent(CalEvent.Cancel) }
        val form = potForm()
        assertEquals("pot-1", form.id)
        assertNull(form.note)
        val intervals = butler.sent("/interval").map { it.body.readUtf8() }
        assertEquals(listOf("c=0 next=5", "c=0 next=120"), intervals)
    }
}
